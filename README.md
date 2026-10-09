# HERALLY

Safety assistant web app built with Java 17, Maven and Gemini/llama.cpp.

## Run

Requires JDK 17+ and Maven.

Windows (PowerShell):
    $env:SERPAPI_KEY="529bc2ebed6ca3390b0323e3be8be49c0f808cddb952cc4c419ae3d60ac5ef7e"
    $env:GEMINI_API_KEY="AQ.Ab8RN6J83RFA0GrpMrq0E51lQyF5PXgr3ckgW2IMe_Rb1OBOYA"
    mvn compile exec:java "-Dexec.mainClass=com.herally.Main"

Then open http://localhost:8081

Set LLM_PROVIDER=llama to use a local llama.cpp server instead of Gemini.