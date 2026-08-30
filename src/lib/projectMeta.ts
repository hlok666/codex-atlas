/** Public project metadata used by the desktop UI and release links.
 *
 * Keep the repository configurable for forks and private mirrors. Release
 * assets are always resolved from GitHub's latest release endpoint.
 */
const repository = (import.meta.env.VITE_GITHUB_REPOSITORY || 'hlok666/codex-atlas').trim().replace(/^https?:\/\/github\.com\//i, '').replace(/\/$/, '')

export const ATLAS_GITHUB_REPOSITORY = repository
export const ATLAS_GITHUB_URL = `https://github.com/${repository}`
export const ATLAS_RELEASES_URL = `${ATLAS_GITHUB_URL}/releases/latest`
export const ATLAS_RELEASE_API_URL = `https://api.github.com/repos/${repository}/releases/latest`
export const ATLAS_VERSION = import.meta.env.VITE_APP_VERSION || '0.1.0'
