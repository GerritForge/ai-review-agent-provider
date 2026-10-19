// Copyright (C) 2026 GerritForge, Inc.
//
// Licensed under the BSL 1.1 (the "License");
// you may not use this file except in compliance with the License.
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.gerritforge.gerrit.plugins.ai.provider.actions;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;

import org.junit.Test;

public class ActionFileParserTest {

  @Test
  public void parsesHeaderAndPrompt() throws Exception {
    ActionDefinition action =
        parse("---\n{\"displayText\": \"Security\", \"contextLines\": 20}\n---\nCheck {{patch}}\n");

    assertThat(action.id()).isEqualTo("security");
    assertThat(action.enabled()).isTrue();
    assertThat(action.displayText()).isEqualTo("Security");
    assertThat(action.contextLines()).isEqualTo(20);
    assertThat(action.prompt()).isEqualTo("Check {{patch}}\n");
    assertThat(action.unknownFields()).isEmpty();
  }

  @Test
  public void omittedFieldsTakeDefaults() throws Exception {
    ActionDefinition action = parse("---\n{\"displayText\": \"Security\"}\n---\nCheck");

    assertThat(action.enabled()).isTrue();
    assertThat(action.contextLines()).isEqualTo(ActionFileParser.DEFAULT_CONTEXT_LINES);
  }

  @Test
  public void keepsPromptVerbatim() throws Exception {
    String prompt = "# Heading\n---\n\"quoted\" ; # not a comment \\n\n  indented  \n";

    ActionDefinition action = parse("---\r\n{\"displayText\": \"Security\"}\r\n---\r\n" + prompt);

    assertThat(action.prompt()).isEqualTo(prompt);
  }

  @Test
  public void acceptsMultilineHeader() throws Exception {
    ActionDefinition action =
        parse("---\n{\n  \"displayText\": \"Security\",\n  \"contextLines\": 0\n}\n---\nCheck");

    assertThat(action.contextLines()).isEqualTo(0);
  }

  @Test
  public void skipsCommentsBeforeHeader() throws Exception {
    ActionDefinition action =
        parse(
            "<!--\nCopyright 2026 Example\n---\nSPDX-License-Identifier: Apache-2.0\n-->\n"
                + "\n<!-- second -->\n---\n{\"displayText\": \"Security\"}\n---\nCheck");

    assertThat(action.displayText()).isEqualTo("Security");
    assertThat(action.prompt()).isEqualTo("Check");
  }

  @Test
  public void acceptsByteOrderMarkAndTrailingSpacesOnFences() throws Exception {
    ActionDefinition action = parse("\uFEFF--- \n{\"displayText\": \"Security\"}\n---\t\nCheck");

    assertThat(action.prompt()).isEqualTo("Check");
  }

  @Test
  public void countsDisplayTextInCharactersNotCodeUnits() throws Exception {
    String emoji = "\uD83D\uDD12".repeat(200);

    assertThat(parse("---\n{\"displayText\": \"" + emoji + "\"}\n---\nCheck").displayText())
        .isEqualTo(emoji);
  }

  @Test
  public void disabledActionNeedsNothingElse() throws Exception {
    ActionDefinition action = parse("---\n{\"enabled\": false}\n---\n");

    assertThat(action.enabled()).isFalse();
    assertThat(action.displayText()).isNull();
    assertThat(action.prompt()).isEmpty();
  }

  @Test
  public void disabledActionWithoutTrailingNewline() throws Exception {
    assertThat(parse("---\n{\"enabled\": false}\n---").enabled()).isFalse();
  }

  @Test
  public void unknownFieldsAreReportedAndIgnored() throws Exception {
    ActionDefinition action =
        parse(
            "---\n{\"displayText\": \"Security\", \"paths\": [\"java/**\"], \"model\": {}}\n"
                + "---\nCheck");

    assertThat(action.unknownFields()).containsExactly("paths", "model").inOrder();
  }

  @Test
  public void rejectsInvalidFiles() {
    assertInvalid("security", "", "must start with a '---' line");
    assertInvalid("security", "Check {{patch}}", "must start with a '---' line");
    assertInvalid("security", "---\n{\"displayText\": \"S\"}\nCheck", "closed by a '---' line");
    assertInvalid("security", "---\n---\nCheck", "not valid JSON");
    assertInvalid("security", "---\n[]\n---\nCheck", "must be a JSON object");
    assertInvalid("security", "---\n{displayText: \"S\"}\n---\nCheck", "not valid JSON");
    assertInvalid("security", "---\n{\"displayText\": 'S'}\n---\nCheck", "not valid JSON");
    assertInvalid("security", "---\n{\"displayText\": \"S\",}\n---\nCheck", "not valid JSON");
    assertInvalid("security", "---\n{\"displayText\": \"S\"} {}\n---\nCheck", "single JSON object");
    assertInvalid(
        "security",
        "---\n{\"displayText\": \"S\", \"displayText\": \"T\"}\n---\nCheck",
        "duplicate field \"displayText\"");
    assertInvalid("security", "---\n{}\n---\nCheck", "\"displayText\" is required");
    assertInvalid("security", "---\n{\"displayText\": \" \"}\n---\nCheck", "must not be blank");
    assertInvalid(
        "security",
        "---\n{\"displayText\": \"" + "x".repeat(201) + "\"}\n---\nCheck",
        "must not be longer than 200");
    assertInvalid("security", "---\n{\"displayText\": \"S\"}\n---\n \n", "prompt");
    assertInvalid("security", "<!-- open\n---\n{}\n---\nCheck", "not closed by -->");
    assertInvalid(
        "security", "<!-- c --> ---\n{\"displayText\": \"S\"}\n---\nC", "must start on a new line");
    assertInvalid("security", "Note\n---\n{\"displayText\": \"S\"}\n---\nC", "must start with");
    assertInvalid(
        "security",
        "---\n{\"displayText\": \"Safe\\u202Eweiver\"}\n---\nC",
        "control or formatting characters");
    assertInvalid(
        "security",
        "---\n{\"displayText\": \"Two\\nlines\"}\n---\nC",
        "control or formatting characters");
    assertInvalid("security", "---\n{\"displayText\": 1}\n---\nCheck", "must be a string");
    assertInvalid("security", "---\n{\"enabled\": \"no\"}\n---\n", "must be true or false");
    assertInvalid("security", "---\n{\"enabled\": null}\n---\n", "must be true or false");
    for (String lines : new String[] {"-1", "1000001", "1.5", "1e3", "\"5\"", "99999999999"}) {
      assertInvalid(
          "security",
          "---\n{\"displayText\": \"S\", \"contextLines\": " + lines + "}\n---\nCheck",
          "\"contextLines\" must be an integer from 0 to 1000000");
    }
  }

  @Test
  public void rejectsWrongTypesInDisabledAction() {
    assertInvalid(
        "security", "---\n{\"enabled\": false, \"contextLines\": \"x\"}\n---\n", "contextLines");
  }

  @Test
  public void rejectsInvalidIds() {
    for (String id : new String[] {"Security", "1security", "-x", "a_b", "a".repeat(65), ""}) {
      assertInvalid(id, "---\n{\"displayText\": \"S\"}\n---\nCheck", "file name must match");
    }
  }

  @Test
  public void acceptsLongestId() throws Exception {
    String id = "a".repeat(64);
    assertThat(ActionFileParser.parse(id, "---\n{\"enabled\": false}\n---".getBytes(UTF_8)).id())
        .isEqualTo(id);
  }

  @Test
  public void rejectsLargeFiles() {
    String file = "---\n{\"displayText\": \"S\"}\n---\n";
    byte[] content =
        (file + "x".repeat(ActionFileParser.MAX_FILE_BYTES - file.length() + 1)).getBytes(UTF_8);

    InvalidActionFileException e =
        assertThrows(
            InvalidActionFileException.class, () -> ActionFileParser.parse("security", content));
    assertThat(e).hasMessageThat().contains("larger than");
  }

  @Test
  public void rejectsInvalidUtf8() {
    byte[] content = {'-', '-', '-', '\n', (byte) 0xC3, '\n', '-', '-', '-', '\n'};

    InvalidActionFileException e =
        assertThrows(
            InvalidActionFileException.class, () -> ActionFileParser.parse("security", content));
    assertThat(e).hasMessageThat().contains("UTF-8");
  }

  @Test
  public void fileNameOnlyForMarkdownFilesDirectlyInFolder() {
    assertThat(ActionFileParser.fileName("ai-review/security.md")).hasValue("security");
    assertThat(ActionFileParser.fileName("ai-review/Security.md")).hasValue("Security");
    assertThat(ActionFileParser.fileName("ai-review/agents/reviewer.md")).isEmpty();
    assertThat(ActionFileParser.fileName("ai-review/skills/x/SKILL.md")).isEmpty();
    assertThat(ActionFileParser.fileName("ai-review/README.txt")).isEmpty();
    assertThat(ActionFileParser.fileName("ai-review.md")).isEmpty();
    assertThat(ActionFileParser.fileName("project.config")).isEmpty();
  }

  @Test
  public void bundledActionsKeepTodaysBehaviour() {
    assertThat(BuiltInActions.get().keySet())
        .containsExactly("review-change", "review-change-full", "review-commit")
        .inOrder();
    assertThat(BuiltInActions.get().get("review-change").contextLines()).isEqualTo(10);
    assertThat(BuiltInActions.get().get("review-change-full").contextLines()).isEqualTo(1000000);
    assertThat(BuiltInActions.get().get("review-commit").contextLines()).isEqualTo(3);
    assertThat(BuiltInActions.get().get("review-change").displayText())
        .isEqualTo("Help me with review");
    assertThat(BuiltInActions.get().get("review-commit").prompt()).contains("{{patch}}");
    assertThat(BuiltInActions.get().get("review-commit").prompt()).doesNotContain("Copyright");
  }

  private static ActionDefinition parse(String content) throws InvalidActionFileException {
    return ActionFileParser.parse("security", content.getBytes(UTF_8));
  }

  private static void assertInvalid(String id, String content, String message) {
    InvalidActionFileException e =
        assertThrows(
            InvalidActionFileException.class,
            () -> ActionFileParser.parse(id, content.getBytes(UTF_8)));
    assertThat(e).hasMessageThat().contains(message);
  }
}
