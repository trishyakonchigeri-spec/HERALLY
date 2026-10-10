# HERALLY

Safety assistant web app built with Java 17 and Maven. It uses SerpApi for search and Gemini (or a local llama.cpp server) for AI responses.

## Requirements

- JDK 17 or newer
- Maven
- A SerpApi key and a Gemini API key

## Run

Windows (PowerShell):

```powershell
$env:SERPAPI_KEY="your_serpapi_key"
$env:GEMINI_API_KEY="your_gemini_key"
mvn compile exec:java "-Dexec.mainClass=com.herally.Main"
```

Then open http://localhost:8081

To use a local llama.cpp server instead of Gemini, set `LLM_PROVIDER=llama`.
