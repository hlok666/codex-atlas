export class FloatingSessionTargetLock {
  selectedSessionId: string | null = null
  lockedSessionId: string | null = null

  selectAutomatically(sessionId: string | null): string | null {
    if (this.lockedSessionId) return this.lockedSessionId
    this.selectedSessionId = sessionId
    return sessionId
  }

  selectManually(sessionId: string): string {
    this.selectedSessionId = sessionId
    if (this.lockedSessionId) this.lockedSessionId = sessionId
    return sessionId
  }

  openInput(sessionId?: string | null): string | null {
    const target = sessionId?.trim() || this.selectedSessionId
    if (!target) return null
    this.selectedSessionId = target
    this.lockedSessionId = target
    return target
  }

  closeInput(): void {
    this.lockedSessionId = null
  }

  submissionTarget(fallbackSessionId?: string | null): string | null {
    return this.lockedSessionId || this.selectedSessionId || fallbackSessionId || null
  }
}
