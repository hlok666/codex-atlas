package com.codexatlas.mobile

/** Public links are kept in one place so forks only need to change one build field. */
object ProjectLinks {
    const val repository = "hlok666/codex-atlas"
    const val projectUrl = "https://github.com/$repository"
    const val releasesUrl = "$projectUrl/releases/latest"
    const val latestReleaseApi = "https://api.github.com/repos/$repository/releases/latest"
}
