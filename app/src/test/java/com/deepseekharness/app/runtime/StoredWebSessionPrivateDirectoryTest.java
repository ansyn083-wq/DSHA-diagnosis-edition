package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class StoredWebSessionPrivateDirectoryTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void canonicalizesSystemProvidedPrivateDirectoryBeforeAnchoredAccess() throws Exception {
    File physical = temporary.newFolder("physical", "no_backup");
    File alias = new File(temporary.getRoot(), "private-alias");
    Files.createSymbolicLink(alias.toPath(), physical.toPath());

    assertEquals(physical.getCanonicalFile(), StoredWebSession.privateDirectory(alias));
  }

  @Test
  public void rejectsMissingPrivateDirectory() {
    assertThrows(IOException.class, () -> StoredWebSession.privateDirectory(null));
  }
}
