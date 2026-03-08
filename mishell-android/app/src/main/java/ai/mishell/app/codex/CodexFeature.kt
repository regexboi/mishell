package ai.mishell.app.codex

import android.content.Context

object CodexFeature {
    @Volatile
    private var repository: CodexSessionRepository? = null

    fun repository(context: Context): CodexSessionRepository {
        val existing = repository
        if (existing != null) {
            return existing
        }
        return synchronized(this) {
            repository ?: CodexSessionRepository(context.applicationContext).also {
                repository = it
            }
        }
    }
}
