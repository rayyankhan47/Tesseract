# Demoday Terminal Commands

Use three terminals: one for Minecraft, one for the plan server, one for the web server.

## 1 Minecraft mod
```bash
cd /Users/rayyan/Desktop/cs/dev/hackathons/tesseract
export GUMLOOP_WEBHOOK_URL="https://api.gumloop.com/api/v1/start_pipeline?api_key=50d96e74be0849609c62088c5102da28&user_id=TM6pLxGuKRd6jcUX2QlrXKlIx9K2&saved_item_id=1SMP5SVWn2pp7WKAekNqHH"
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
export GUMLOOP_WEBHOOK_URL="https://api.gumloop.com/api/v1/start_pipeline?api_key=50d96e74be0849609c62088c5102da28&user_id=TM6pLxGuKRd6jcUX2QlrXKlIx9K2&saved_item_id=1SMP5SVWn2pp7WKAekNqHH"
export GUMLOOP_SKIP_SSL_VERIFY=1
python3 server.py
```
