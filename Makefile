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

.PHONY: build build-ui build-all install install-ui install-all uninstall uninstall-ui start stop restart logs dev check-env

build:
	./mvnw -pl herald-bot package -DskipTests

build-ui:
	./mvnw -pl herald-ui package -DskipTests

build-all:
	./mvnw package -DskipTests

check-env:
	@if [ -z "$$TELEGRAM_BOT_TOKEN" ]; then echo "WARNING: TELEGRAM_BOT_TOKEN is not set"; fi
	@if [ -z "$$ANTHROPIC_API_KEY" ]; then echo "WARNING: ANTHROPIC_API_KEY is not set"; fi

install: build check-env
	@mkdir -p $(HERALD_HOME)
	@mkdir -p $(HOME)/Library/LaunchAgents
	@mkdir -p $(HOME)/Library/Logs
	cp $(JAR_SOURCE) $(JAR_DEST)
	sed 's|__HOME__|$(HOME)|g' $(PLIST) > $(PLIST_DEST)
	launchctl bootstrap $(GUI_TARGET) $(PLIST_DEST)
	@echo "Herald service installed and started."

install-ui: build-ui
	@mkdir -p $(HERALD_HOME)
	@mkdir -p $(HOME)/Library/LaunchAgents
	@mkdir -p $(HOME)/Library/Logs
	cp $(UI_JAR_SOURCE) $(UI_JAR_DEST)
	sed 's|__HOME__|$(HOME)|g' $(UI_PLIST) > $(UI_PLIST_DEST)
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
	launchctl bootstrap $(GUI_TARGET) $(PLIST_DEST)

stop:
	launchctl bootout $(GUI_TARGET)/$(LABEL)

restart: stop start

logs:
	tail -f $(LOG_FILE)

dev:
	./mvnw -pl herald-bot spring-boot:run
