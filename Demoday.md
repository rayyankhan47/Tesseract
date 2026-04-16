# Demoday Terminal Commands

Use three terminals: one for Minecraft, one for the plan server, one for the web server.

## 1 Minecraft mod
```bash
cd /Users/rayyan/Desktop/cs/dev/hackathons/tesseract
export GEMINI_API_KEY="your_gemini_api_key_here"
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew runClient
```

## 2 Plan store server (port 4890)
Stores generated plans and returns shareable URLs for `/tesseract paste <url>`.
```bash
cd /Users/rayyan/Desktop/cs/dev/hackathons/tesseract
python3 tools/plan_server.py
```

## 3 Web server (port 5173)
Dashboard UI. Calls the Java mod's build endpoint (port 4891) then stores the plan.
```bash
cd /Users/rayyan/Desktop/cs/dev/hackathons/tesseract/web
python3 server.py
```

## Ports at a glance
| Port | Service |
|------|---------|
| 4890 | `tools/plan_server.py` — plan store (GET/POST /plans) |
| 4891 | Java mod embedded HTTP server — agent pipeline (POST /build) |
| 5173 | `web/server.py` — dashboard UI |

## Environment variables
| Variable | Required by | Description |
|----------|-------------|-------------|
| `GEMINI_API_KEY` | Java mod | Gemini API key for all LLM calls |
| `BUILD_SERVER_URL` | web/server.py | Override Java mod URL (default: http://localhost:4891/build) |
| `PLAN_SERVER_URL` | web/server.py | Override plan store URL (default: http://localhost:4890/plans) |
