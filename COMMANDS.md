# Commands

Minecraft **1.18.2**. Needs Java 17 and Python 3.8+.

## One-time setup

```bash
# .env in the project root
echo 'GEMINI_API_KEY=your_key_here' > .env
```

## Run (3 terminals)

```bash
# 1. Plan store (port 4890)
python3 tools/plan_server.py

# 2. Web dashboard (port 5173)
cd web && python3 server.py

# 3. Minecraft mod (must use Java 17 explicitly)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
GRADLE_USER_HOME=/Users/rayyan/.gradle-jdk17 \
./gradlew runClient
```

## In-game commands

```
/tesseract build <prompt>      run the full agent pipeline
/tesseract paste <url>         place a web-generated plan instantly
/tesseract demo cabin          preset demo build
/tesseract demo gate           preset demo build
/tesseract clear               clear the build selection
/tesseract context clear       clear the context selection
```

## Build / compile only

```bash
GRADLE_USER_HOME=/Users/rayyan/.gradle-jdk17 ./gradlew compileJava
GRADLE_USER_HOME=/Users/rayyan/.gradle-jdk17 ./gradlew build
# JAR output: build/libs/
```
