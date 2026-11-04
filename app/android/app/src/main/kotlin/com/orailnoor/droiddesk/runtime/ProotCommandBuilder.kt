package com.orailnoor.droiddesk.runtime

import java.io.File

data class ProotBind(
    val host: File,
    val guest: String,
)

class ProotCommandBuilder(
    private val prootDistro: File,
    private val prefixDir: File,
    private val hostHome: File,
    private val tmpDir: File,
    private val packageName: String,
    approvedHostRoots: List<File>,
) {
    private val approvedRoots = approvedHostRoots.map(File::getCanonicalFile)

    fun login(
        container: String,
        arguments: List<String>,
        environment: Map<String, String>,
        binds: List<ProotBind>,
        user: String = "droiddesk",
    ): List<String> {
        require(container.matches(Regex("[a-z0-9][a-z0-9_.-]*"))) {
            "Invalid PRoot container name: $container"
        }
        require(user.matches(Regex("[a-z_][a-z0-9_-]*"))) {
            "Invalid PRoot user: $user"
        }
        val command = tool(
            listOf(
                "login",
                container,
                "--bind",
                "${approved(tmpDir)}:/tmp",
            ),
        ).toMutableList()
        binds.forEach { bind ->
            require(bind.guest.startsWith("/")) {
                "PRoot bind destination must be absolute: ${bind.guest}"
            }
            command += listOf("--bind", "${approved(bind.host)}:${bind.guest}")
        }
        environment.toSortedMap().forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                "Invalid environment name: $key"
            }
            command += listOf("--env", "$key=$value")
        }
        command += listOf("--user", user, "--")
        command += arguments
        return command
    }

    fun tool(arguments: List<String>): List<String> =
        listOf(
            "env",
            "TERMUX__PREFIX=${prefixDir.canonicalPath}",
            "TERMUX__HOME=${hostHome.canonicalPath}",
            "TERMUX_APP__PACKAGE_NAME=$packageName",
            prootDistro.canonicalPath,
        ) + arguments

    private fun approved(file: File): String {
        val canonical = file.canonicalFile
        val allowed = approvedRoots.any { root ->
            canonical == root || canonical.path.startsWith(root.path + File.separator)
        }
        require(allowed) { "Host bind is outside approved roots: ${canonical.path}" }
        return canonical.path
    }
}
