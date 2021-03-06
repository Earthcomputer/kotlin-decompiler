/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler;

import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.junit.jupiter.api.BeforeEach;

import static org.jetbrains.java.decompiler.SingleClassesTestBase.TestDefinition.Version.*;

import java.io.IOException;

public class LVTTest extends SingleClassesTestBase {
  @Override
  protected String[] getDecompilerOptions() {
    return new String[] {
      IFernflowerPreferences.DECOMPILE_INNER,"1",
      IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES,"1",
      IFernflowerPreferences.ASCII_STRING_CHARACTERS,"1",
      IFernflowerPreferences.LOG_LEVEL, "TRACE",
      IFernflowerPreferences.REMOVE_SYNTHETIC, "1",
      IFernflowerPreferences.REMOVE_BRIDGE, "1",
      IFernflowerPreferences.USE_DEBUG_VAR_NAMES, "1"
    };
  }

  @Override
  @BeforeEach
  public void setUp() throws IOException {
      super.setUp();
      fixture.setCleanup(false);
  }

  @Override
  protected void registerAll() {
    register(JAVA_8, "TestLVT");
    register(JAVA_8, "TestLVTScoping");
    // TODO: int is decompiling as <unknown>
    // register(JAVA_8, "TestLVTComplex");
    register(JAVA_8, "TestVarType");
    register(JAVA_8, "TestLoopMerging");
    // TODO: this is not decompiling properly, needs a look
    // register(JAVA_8, "TestPPMM");
  }
}
