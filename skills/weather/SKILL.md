---
name: weather
description: >
  Looks up current weather conditions for any city using the wttr.in REST API.
  Use when asked about weather, temperature, or forecast for a location.
---

# Weather Lookup Skill

Retrieve current weather information using the [wttr.in](https://wttr.in) service.

## Usage

### Quick Weather Summary

```bash
curl -s 'wttr.in/{location}?format=3'
```

- Replace `{location}` with the city name (e.g., `Denver`, `London`, `Tokyo`).
- URL-encode spaces: `New+York`, `San+Francisco`.
- Returns a one-line summary: location, condition icon, and temperature.

**Use for:** "What's the weather in Denver?", "Is it raining in Seattle?", "Temperature in Tokyo?"

### Detailed Weather (JSON)

```bash
curl -s 'wttr.in/{location}?format=j1'
```

- Returns detailed JSON with current conditions and a 3-day forecast.
- Key fields in the JSON response:
  - `current_condition[0]`:
    - `temp_F` / `temp_C` — current temperature
    - `weatherDesc[0].value` — condition description (e.g., "Partly cloudy")
    - `humidity` — relative humidity percentage
    - `windspeedMiles` / `windspeedKmph` — wind speed
    - `FeelsLikeF` / `FeelsLikeC` — feels-like temperature
    - `uvIndex` — UV index
  - `weather[]` — 3-day forecast array, each with:
    - `date` — forecast date
    - `maxtempF` / `mintempF` — high/low temperatures
    - `hourly[]` — hourly breakdown

**Use for:** "Give me the full forecast for Chicago", "What's the humidity in Miami?", "3-day forecast for Boston"

## Response Formatting

Format responses as clean, readable messages:

- **Quick check:** "Denver: Partly cloudy, 72°F (22°C)"
- **Detailed:** Include current conditions, feels-like, humidity, wind, and a brief 3-day outlook.
- **Always include both Fahrenheit and Celsius.**

### Example Response

```
**Weather in Denver, CO**
Currently: Partly cloudy, 72°F (22°C)
Feels like: 70°F (21°C)
Humidity: 35% | Wind: 8 mph
UV Index: 5 (Moderate)

**3-Day Forecast:**
• Today: High 78°F / Low 55°F — Sunny
• Tomorrow: High 75°F / Low 52°F — Partly cloudy
• Wednesday: High 68°F / Low 48°F — Chance of rain
```

## Error Handling

| Error | Response |
|-------|----------|
| Unknown location / empty response | "Couldn't find weather data for that location. Check the city name and try again." |
| Network error / curl failure | "Unable to reach the weather service. Check your internet connection." |
| Garbled or HTML response | The API may return HTML if the location is ambiguous. Ask the user to be more specific (e.g., "Portland, OR" vs. "Portland, ME"). |
