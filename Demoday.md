# Demoday Terminal Commands

Use three terminals: one for Minecraft, one for the plan server, one for the web server.

## 1 Minecraft mod
```bash
cd /Users/rayyan/Desktop/cs/dev/hackathons/tesseract
export GUMLOOP_WEBHOOK_URL="YOUR_GUMLOOP_WEBHOOK_URL"
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew runClient
```

## 2 URL tools (plan server)
```bash
cd /Users/rayyan/Desktop/cs/dev/hackathons/tesseract
python3 tools/plan_server.py
```

## 3 Web server
```bash
cd /Users/rayyan/Desktop/cs/dev/hackathons/tesseract/web
export GUMLOOP_WEBHOOK_URL="YOUR_GUMLOOP_WEBHOOK_URL"
export GUMLOOP_SKIP_SSL_VERIFY=1
python3 server.py
```
