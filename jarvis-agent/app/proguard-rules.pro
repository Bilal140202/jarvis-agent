# Jarvis Agent ProGuard Rules

# Keep Moshi adapters
-keep class com.jarvis.agent.llm.** { *; }
-keepclassmembers class com.jarvis.agent.llm.** {
    <fields>;
}

# Keep accessibility service
-keep class com.jarvis.agent.service.AgentAccessibilityService { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
