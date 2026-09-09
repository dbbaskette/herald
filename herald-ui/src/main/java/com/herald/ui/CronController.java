package com.herald.ui;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DuplicateKeyException;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.herald.ui.repository.CommandRepository;
import com.herald.ui.repository.CronJobRepository;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping({"/api/cron", "/api/cron-jobs"})
class CronController {
    private final CronJobRepository jobs;
    private final CommandRepository commands;
    private final ZoneId timezone;

    CronController(CronJobRepository jobs, CommandRepository commands,
            @Value("${herald.cron.timezone:${HERALD_CRON_TIMEZONE:America/New_York}}") String timezone) {
        this.jobs = jobs; this.commands = commands; this.timezone = ZoneId.of(timezone);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> invalid(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason() == null ? "Request failed" : error.getReason()));
    }

    @GetMapping
    List<CronJobDto> list() { return jobs.listAll().stream().map(this::dto).toList(); }

    @PostMapping
    @Transactional
    ResponseEntity<CronJobDto> create(@RequestBody CronJobRequest request) {
        validate(request.name(), request.expression(), request.promptText());
        try {
            long id = jobs.create(request.name(), request.expression(), request.promptText(), !Boolean.FALSE.equals(request.enabled()));
            commands.insert("SYNC_CRON", String.valueOf(id));
            return ResponseEntity.status(CREATED).body(dto(jobs.getById(id)));
        } catch (DuplicateKeyException e) { throw new ResponseStatusException(CONFLICT, "A job with this name already exists"); }
    }

    @RequestMapping(value="/{id}", method={RequestMethod.PUT, RequestMethod.PATCH})
    @Transactional
    ResponseEntity<CronJobDto> update(@PathVariable long id, @RequestBody CronJobRequest request) {
        Map<String,Object> old = required(id);
        String name = request.name() == null ? (String) old.get("name") : request.name();
        String expression = request.expression() == null ? (String) old.get("schedule") : request.expression();
        String prompt = request.promptText() == null ? (String) old.get("prompt") : request.promptText();
        boolean enabled = request.enabled() == null ? flag(old.get("enabled")) : request.enabled();
        if (flag(old.get("built_in")) && !name.equals(old.get("name")))
            throw new ResponseStatusException(BAD_REQUEST, "Built-in job names cannot be changed");
        validate(name, expression, prompt);
        try { jobs.update(id, name, expression, prompt, enabled); }
        catch (DuplicateKeyException e) { throw new ResponseStatusException(CONFLICT, "A job with this name already exists"); }
        commands.insert("SYNC_CRON", String.valueOf(id));
        return ResponseEntity.ok(dto(jobs.getById(id)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    ResponseEntity<Void> delete(@PathVariable long id) {
        if (flag(required(id).get("built_in"))) throw new ResponseStatusException(BAD_REQUEST, "Built-in jobs cannot be deleted");
        if (!jobs.delete(id)) throw new ResponseStatusException(CONFLICT, "Job could not be deleted");
        commands.insert("SYNC_CRON", String.valueOf(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/run")
    @Transactional
    ResponseEntity<Map<String,Object>> run(@PathVariable long id) {
        Map<String,Object> job = required(id);
        parse((String) job.get("schedule"));
        return ResponseEntity.status(ACCEPTED).body(commands.insert("RUN_CRON", String.valueOf(id)));
    }

    private Map<String,Object> required(long id) {
        Map<String,Object> job = jobs.getById(id);
        if (job == null) throw new ResponseStatusException(NOT_FOUND, "Cron job not found");
        return job;
    }
    private static boolean flag(Object value) { return value instanceof Number n && n.intValue() == 1; }
    static CronExpression parse(String expression) {
        if (expression == null || expression.length() > 256) throw new ResponseStatusException(BAD_REQUEST, "Provide a cron expression with five or six fields");
        String normalized = expression.trim();
        if (normalized.split("\\s+").length == 5) normalized = "0 " + normalized;
        try { return CronExpression.parse(normalized); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(BAD_REQUEST, "Invalid cron expression: " + e.getMessage()); }
    }
    static String nextRun(String expression, ZoneId zone, ZonedDateTime now) {
        var next = parse(expression).next(now.withZoneSameInstant(zone));
        return next == null ? null : next.toOffsetDateTime().toString();
    }
    private void validate(String name, String expression, String prompt) {
        if (name == null || name.isBlank() || name.length() > 200) throw new ResponseStatusException(BAD_REQUEST, "Name is required (maximum 200 characters)");
        if (prompt == null || prompt.isBlank() || prompt.length() > 100000) throw new ResponseStatusException(BAD_REQUEST, "Prompt is required (maximum 100000 characters)");
        parse(expression);
    }
    private CronJobDto dto(Map<String,Object> row) {
        long id = ((Number) row.get("id")).longValue();
        boolean enabled = flag(row.get("enabled"));
        String expression = (String) row.get("schedule");
        ZoneId zone = ZoneId.of(jobs.runtimeTimezone(timezone.toString()));
        String next = null;
        String error = null;
        try { if (enabled) next = nextRun(expression, zone, ZonedDateTime.now(zone)); }
        catch (ResponseStatusException e) { error = e.getReason(); }
        Map<String,Object> state = jobs.execution(id);
        String status = state == null ? "idle" : (String) state.get("status");
        String active = jobs.activeRunStatus(id);
        if (active != null) status = active;
        return new CronJobDto(String.valueOf(id), (String) row.get("name"), expression,
                (String) row.get("prompt"), enabled, flag(row.get("built_in")), zone.toString(),
                row.get("last_run") == null ? null : row.get("last_run").toString(), next,
                status, error != null ? error : state == null ? null : (String) state.get("message"), jobs.syncStatus(id));
    }
    record CronJobRequest(String name, @JsonAlias("schedule") String expression,
            @JsonAlias("prompt") String promptText, Boolean enabled) {}
    record CronJobDto(String id, String name, String expression, String promptText, boolean enabled,
            boolean builtIn, String timezone, String lastRun, String nextRun, String status,
            String lastRunLog, String scheduleStatus) {}
}
