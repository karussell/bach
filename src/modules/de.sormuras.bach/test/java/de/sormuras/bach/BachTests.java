/*
 * Bach - Java Shell Builder
 * Copyright (C) 2019 Christian Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.sormuras.bach;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ginsberg.junit.exit.ExpectSystemExitWithStatus;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class BachTests {

  private final TestRun test = new TestRun();
  private final Bach bach = new Bach(test);

  @Test
  void userPathIsCurrentWorkingDirectory() {
    assertEquals(Path.of(".").normalize().toAbsolutePath(), Bach.USER_PATH);
  }

  @Test
  void userHomeIsUsersHome() {
    assertEquals(Path.of(System.getProperty("user.home")), Bach.USER_HOME);
  }

  @Test
  void hasPublicStaticVoidMainWithVarArgs() throws Exception {
    var main = Bach.class.getMethod("main", String[].class);
    assertTrue(Modifier.isPublic(main.getModifiers()));
    assertTrue(Modifier.isStatic(main.getModifiers()));
    assertSame(void.class, main.getReturnType());
    assertEquals("main", main.getName());
    assertTrue(main.isVarArgs());
    assertEquals(0, main.getExceptionTypes().length);
  }

  @Test
  @SwallowSystem
  void callingStaticVoidMainDoesNotThrow(SwallowSystem.Streams streams) {
    var dryRun = "Dry-run".substring(1);
    try {
      System.setProperty(dryRun, "");
      assertDoesNotThrow((Executable) Bach::main);
    } finally {
      System.getProperties().remove(dryRun);
    }
  }

  @Test
  @SwallowSystem
  @ExpectSystemExitWithStatus(1)
  void callingStaticMainWithIllegalActionNameExitsSystemWithErrorStatus1(SwallowSystem.Streams __) {
    Bach.main("unsupported action");
  }

  @Test
  void runEmptyCollectionOfArgumentsPerformsDefaultAction() {
    assertEquals(0, bach.main(List.of()));
    assertLinesMatch(
        List.of(
            "Bach (master) initialized",
            ">> INITIALIZATION >>",
            "Performing 1 action(s)...",
            ">> DEFAULT ACTION LINES >>"),
        test.outLines());
  }

  @Test
  void runEmptyCollectionOfActions() {
    assertEquals(0, bach.run(List.of()));
    assertLinesMatch(
        List.of("Bach (master) initialized", "Bach::run([])", "Performing 0 action(s)..."),
        test.outLines());
  }

  @Test
  void runNoopActionReturnsZero() {
    assertEquals(0, bach.run(List.of(bach -> {})));
    assertLinesMatch(
        List.of(
            "Bach (master) initialized",
            "Bach::run.+",
            "Performing 1 action(s)...",
            "\\Q>> de.sormuras.bach.BachTests$$Lambda$\\E.+",
            "\\Q<< de.sormuras.bach.BachTests$$Lambda$\\E.+"),
        test.outLines());
  }

  @Test
  void runThrowingAction() {
    var action = new ThrowingAction();
    assertEquals(1, bach.run(List.of(action)));
    assertLinesMatch(
        List.of(
            "Bach (master) initialized",
            "Bach::run.+",
            "Performing 1 action(s)...",
            "\\Q>> de.sormuras.bach.BachTests$ThrowingAction@\\E.+"),
        test.outLines());
    assertLinesMatch(
        List.of(
            "Action " + action + " threw: java.lang.UnsupportedOperationException: 123",
            "java.lang.UnsupportedOperationException: 123",
            ">> STACKTRACE >>"),
        test.errLines());
  }

  @Test
  void toStringReturnsNameAndVersion() {
    assertEquals("Bach (" + Bach.VERSION + ")", bach.toString());
  }

  private static class ThrowingAction implements Action {

    @Override
    public void perform(Bach bach) {
      throw new UnsupportedOperationException("123");
    }
  }
}
