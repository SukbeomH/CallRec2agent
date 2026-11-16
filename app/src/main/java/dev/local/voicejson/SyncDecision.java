package dev.local.voicejson;

final class SyncDecision {
    enum Action { CREATE, KEEP, UPDATE, CONFLICT }
    static Action choose(String sourceHash, String remoteHash, String lastWrittenHash) {
        if (remoteHash == null) return Action.CREATE;
        if (sourceHash.equals(remoteHash)) return Action.KEEP;
        if (remoteHash.equals(lastWrittenHash)) return Action.UPDATE;
        return Action.CONFLICT;
    }
}
