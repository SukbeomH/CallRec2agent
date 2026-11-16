package dev.local.voicejson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SyncDecisionTest {
    @Test public void createsMissingFile() { assertEquals(SyncDecision.Action.CREATE, SyncDecision.choose("new",null,null)); }
    @Test public void reusesIdenticalUnmanagedFile() { assertEquals(SyncDecision.Action.KEEP, SyncDecision.choose("same","same",null)); }
    @Test public void updatesOnlyUnchangedFileWrittenByThisApp() { assertEquals(SyncDecision.Action.UPDATE, SyncDecision.choose("new","old","old")); }
    @Test public void preservesUnmanagedDifferentFile() { assertEquals(SyncDecision.Action.CONFLICT, SyncDecision.choose("new","other",null)); }
    @Test public void preservesRemoteUserEdits() { assertEquals(SyncDecision.Action.CONFLICT, SyncDecision.choose("new","user-edited","old")); }
    @Test public void unchangedManagedFileNeedsNoRewrite() { assertEquals(SyncDecision.Action.KEEP, SyncDecision.choose("same","same","same")); }
}
