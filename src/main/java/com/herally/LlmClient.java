package com.herally;

/** Anything that can turn a prompt into text. Lets us swap Gemini for a local model. */
public interface LlmClient {
    /** @param json if true, the reply should be raw JSON only. */
    String generate(String prompt, boolean json) throws Exception;
}
