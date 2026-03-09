PLIST       := com.herald.plist
PLIST_DEST  := ~/Library/LaunchAgents/$(PLIST)
LABEL       := com.herald
HERALD_HOME := ~/.herald
JAR_SOURCE  := herald-bot/target/herald-bot-0.1.0-SNAPSHOT.jar
JAR_DEST    := $(HERALD_HOME)/herald-bot.jar
LOG_FILE    := ~/Library/Logs/herald.log

.PHONY: build install uninstall start stop restart logs dev

build:
	./mvnw -pl herald-bot package -DskipTests

install: build
	@mkdir -p $(HERALD_HOME)
	cp $(JAR_SOURCE) $(JAR_DEST)
	cp $(PLIST) $(PLIST_DEST)
	launchctl load $(PLIST_DEST)
	@echo "Herald service installed and started."

uninstall:
	-launchctl unload $(PLIST_DEST)
	rm -f $(PLIST_DEST)
	@echo "Herald service uninstalled."

start:
	launchctl load $(PLIST_DEST)

stop:
	launchctl unload $(PLIST_DEST)

restart: stop start

logs:
	tail -f $(LOG_FILE)

dev:
	./mvnw -pl herald-bot spring-boot:run
