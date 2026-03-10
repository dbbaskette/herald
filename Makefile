PLIST       := com.herald.plist
PLIST_DEST  := $(HOME)/Library/LaunchAgents/$(PLIST)
LABEL       := com.herald
HERALD_HOME := $(HOME)/.herald
JAR_SOURCE  := herald-bot/target/herald-bot-0.1.0-SNAPSHOT.jar
JAR_DEST    := $(HERALD_HOME)/herald-bot.jar
LOG_FILE    := $(HOME)/Library/Logs/herald.log
GUI_TARGET  := gui/$(shell id -u)

UI_PLIST      := com.herald-ui.plist
UI_PLIST_DEST := $(HOME)/Library/LaunchAgents/$(UI_PLIST)
UI_LABEL      := com.herald-ui
UI_JAR_SOURCE := herald-ui/target/herald-ui-0.1.0-SNAPSHOT.jar
UI_JAR_DEST   := $(HERALD_HOME)/herald-ui.jar
UI_LOG_FILE   := $(HOME)/Library/Logs/herald-ui.log

JAVA_HOME_BIN := $(shell /usr/libexec/java_home 2>/dev/null)/bin/java
JAVA_BIN      := $(if $(wildcard $(JAVA_HOME_BIN)),$(JAVA_HOME_BIN),/usr/bin/java)

.PHONY: build build-ui build-all install install-ui install-all uninstall uninstall-ui start start-ui stop stop-ui restart restart-ui logs dev check-env

build:
	./mvnw -pl herald-bot package -DskipTests

build-ui:
	./mvnw -pl herald-ui package -DskipTests

build-all:
	./mvnw package -DskipTests

check-env:
	@fail=0; \
	if [ -z "$$ANTHROPIC_API_KEY" ]; then echo "ERROR: ANTHROPIC_API_KEY is not set"; fail=1; fi; \
	if [ -z "$$HERALD_TELEGRAM_BOT_TOKEN" ]; then echo "ERROR: HERALD_TELEGRAM_BOT_TOKEN is not set"; fail=1; fi; \
	if [ -z "$$HERALD_TELEGRAM_ALLOWED_CHAT_ID" ]; then echo "ERROR: HERALD_TELEGRAM_ALLOWED_CHAT_ID is not set"; fail=1; fi; \
	if [ "$$fail" = "1" ]; then echo "Set required env vars or populate .env before installing."; exit 1; fi

install: build check-env
	@mkdir -p $(HERALD_HOME)
	@mkdir -p $(HOME)/Library/LaunchAgents
	@mkdir -p $(HOME)/Library/Logs
	cp $(JAR_SOURCE) $(JAR_DEST)
	sed -e 's|__HOME__|$(HOME)|g' \
	    -e 's|__JAVA_BIN__|$(JAVA_BIN)|g' \
	    -e 's|__HERALD_TELEGRAM_BOT_TOKEN__|$(HERALD_TELEGRAM_BOT_TOKEN)|g' \
	    -e 's|__ANTHROPIC_API_KEY__|$(ANTHROPIC_API_KEY)|g' \
	    -e 's|__HERALD_TELEGRAM_ALLOWED_CHAT_ID__|$(HERALD_TELEGRAM_ALLOWED_CHAT_ID)|g' \
	    -e 's|__OPENAI_API_KEY__|$(OPENAI_API_KEY)|g' \
	    -e 's|__GCAL_MCP_URL__|$(GCAL_MCP_URL)|g' \
	    -e 's|__GMAIL_MCP_URL__|$(GMAIL_MCP_URL)|g' \
	    $(PLIST) > $(PLIST_DEST)
	launchctl bootstrap $(GUI_TARGET) $(PLIST_DEST)
	@echo "Herald service installed and started."

install-ui: build-ui
	@mkdir -p $(HERALD_HOME)
	@mkdir -p $(HOME)/Library/LaunchAgents
	@mkdir -p $(HOME)/Library/Logs
	cp $(UI_JAR_SOURCE) $(UI_JAR_DEST)
	sed -e 's|__HOME__|$(HOME)|g' \
	    -e 's|__JAVA_BIN__|$(JAVA_BIN)|g' \
	    $(UI_PLIST) > $(UI_PLIST_DEST)
	launchctl bootstrap $(GUI_TARGET) $(UI_PLIST_DEST)
	@echo "Herald UI service installed and started."

install-all: install install-ui

uninstall:
	-launchctl bootout $(GUI_TARGET)/$(LABEL)
	rm -f $(PLIST_DEST)
	@echo "Herald service uninstalled."

uninstall-ui:
	-launchctl bootout $(GUI_TARGET)/$(UI_LABEL)
	rm -f $(UI_PLIST_DEST)
	@echo "Herald UI service uninstalled."

start:
	launchctl kickstart -k $(GUI_TARGET)/$(LABEL)

start-ui:
	launchctl kickstart -k $(GUI_TARGET)/$(UI_LABEL)

stop:
	-launchctl kill SIGTERM $(GUI_TARGET)/$(LABEL)

stop-ui:
	-launchctl kill SIGTERM $(GUI_TARGET)/$(UI_LABEL)

restart:
	launchctl kickstart -k $(GUI_TARGET)/$(LABEL)

restart-ui:
	launchctl kickstart -k $(GUI_TARGET)/$(UI_LABEL)

logs:
	tail -f $(LOG_FILE)

dev:
	./mvnw -pl herald-bot spring-boot:run
