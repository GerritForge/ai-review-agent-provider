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
    ActionDefinition.Enabled action =
        parse("---\n{\"displayText\": \"Security\", \"contextLines\": 20}\n---\nCheck {{patch}}\n");

    assertThat(action.id()).isEqualTo("security");
    assertThat(action.displayText()).isEqualTo("Security");
    assertThat(action.contextLines()).isEqualTo(20);
    assertThat(action.prompt()).isEqualTo("Check {{patch}}\n");
  }

  @Test
  public void omittedFieldsTakeDefaults() throws Exception {
    ActionDefinition.Enabled action = parse("---\n{\"displayText\": \"Security\"}\n---\nCheck");

    assertThat(action.contextLines()).isEqualTo(ActionFileParser.DEFAULT_CONTEXT_LINES);
  }

  @Test
  public void keepsPromptVerbatim() throws Exception {
    String prompt = "# Heading\n---\n\"quoted\" ; # not a comment \\n\n  indented  \n";

    ActionDefinition.Enabled action =
        parse("---\r\n{\"displayText\": \"Security\"}\r\n---\r\n" + prompt);

    assertThat(action.prompt()).isEqualTo(prompt);
  }

  @Test
  public void acceptsMultilineHeader() throws Exception {
    ActionDefinition.Enabled action =
        parse("---\n{\n  \"displayText\": \"Security\",\n  \"contextLines\": 0\n}\n---\nCheck");

    assertThat(action.contextLines()).isEqualTo(0);
  }

  @Test
  public void acceptsByteOrderMarkAndTrailingSpacesOnFences() throws Exception {
    ActionDefinition.Enabled action =
        parse("\uFEFF--- \n{\"displayText\": \"Security\"}\n---\t\nCheck");

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
    assertThat(parseAny("---\n{\"enabled\": false}\n---\n"))
        .isEqualTo(new ActionDefinition.Disabled("security"));
  }

  @Test
  public void disabledActionWithoutTrailingNewline() throws Exception {
    assertThat(parseAny("---\n{\"enabled\": false}\n---"))
        .isInstanceOf(ActionDefinition.Disabled.class);
  }

  @Test
  public void unknownFieldsAreIgnored() throws Exception {
    ActionDefinition.Enabled action =
        parse(
            "---\n{\"displayText\": \"Security\", \"hoverText\": \"S\", \"model\": {}}\n"
                + "---\nCheck");

    assertThat(action.displayText()).isEqualTo("Security");
  }

  @Test
  public void reportsUnknownFieldsInFileOrder() throws Exception {
    ActionFileParser.Parsed parsed =
        ActionFileParser.parseWithUnknownFields(
            "security",
            "---\n{\"hoverText\": \"H\", \"displayText\": \"S\", \"model\": {}}\n---\nCheck"
                .getBytes(UTF_8));

    assertThat(parsed.unknownFields()).containsExactly("hoverText", "model").inOrder();
    assertThat(parsed.definition()).isInstanceOf(ActionDefinition.Enabled.class);
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
    assertInvalid(
        "security",
        "---\n{\"displayText\": \"S\", \"paths\": [\"java/**\"]}\n---\nCheck",
        "\"paths\" is not supported yet");
    assertInvalid("security", "---\n{\"enabled\": false, \"paths\": []}\n---", "\"paths\"");
    assertInvalid("security", "---\n{}\n---\nCheck", "\"displayText\" is required");
    assertInvalid("security", "---\n{\"displayText\": \" \"}\n---\nCheck", "must not be blank");
    assertInvalid(
        "security",
        "---\n{\"displayText\": \"" + "x".repeat(201) + "\"}\n---\nCheck",
        "must not be longer than 200");
    assertInvalid("security", "---\n{\"displayText\": \"S\"}\n---\n \n", "prompt");
    assertInvalid("security", "Note\n---\n{\"displayText\": \"S\"}\n---\nC", "must start with");
    assertInvalid("security", "\n---\n{\"displayText\": \"S\"}\n---\nC", "must start with");
    assertInvalid(
        "security", "<!-- licence -->\n---\n{\"displayText\": \"S\"}\n---\nC", "must start with");
    assertInvalid(
        "security",
        "---\n{\"displayText\": \"Safe\\u202Eweiver\"}\n---\nC",
        "control or formatting characters");
    for (String invisible : new String[] {"\\n", "\\u2028", "\\u2029", "\\ud800"}) {
      assertInvalid(
          "security",
          "---\n{\"displayText\": \"Two" + invisible + "lines\"}\n---\nC",
          "control or formatting characters");
    }
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
  public void acceptsLargestContextLines() throws Exception {
    assertThat(
            parse("---\n{\"displayText\": \"S\", \"contextLines\": 1000000}\n---\nC")
                .contextLines())
        .isEqualTo(1000000);
  }

  @Test
  public void explainsMalformedJsonWithoutAdviceForDevelopers() {
    InvalidActionFileException e = parseError("---\n{displayText: \"S\"}\n---\nCheck");

    assertThat(e).hasMessageThat().contains("malformed JSON");
    assertThat(e).hasMessageThat().doesNotContain("setStrictness");
    assertThat(e).hasMessageThat().doesNotContain("\n");
  }

  @Test
  public void shortensFieldNamesInErrors() {
    String name = "a\\n" + "x".repeat(1000);

    InvalidActionFileException e =
        parseError("---\n{\"" + name + "\": 1, \"" + name + "\": 2}\n---\nC");

    assertThat(e).hasMessageThat().startsWith("header has duplicate field \"a?xxx");
    assertThat(e).hasMessageThat().doesNotContain("\n");
    assertThat(e.getMessage().length()).isLessThan(200);
  }

  @Test
  public void rejectsWrongTypesInDisabledAction() {
    assertInvalid(
        "security", "---\n{\"enabled\": false, \"contextLines\": \"x\"}\n---\n", "contextLines");
  }

  @Test
  public void rejectsInvalidIds() {
    for (String id : new String[] {"Security", "1security", "-x", "a_b", "a".repeat(65), ""}) {
      assertInvalid(id, "---\n{\"displayText\": \"S\"}\n---\nCheck", "file path must match");
    }
  }

  @Test
  public void acceptsLongestId() throws Exception {
    String id = "a".repeat(64);
    assertThat(ActionFileParser.parse(id, "---\n{\"enabled\": false}\n---".getBytes(UTF_8)).id())
        .isEqualTo(id);
  }

  @Test
  public void rejectsInvalidUtf8() {
    byte[] content = {'-', '-', '-', '\n', (byte) 0xC3, '\n', '-', '-', '-', '\n'};

    InvalidActionFileException e =
        assertThrows(
            InvalidActionFileException.class, () -> ActionFileParser.parse("security", content));
    assertThat(e).hasMessageThat().contains("UTF-8");
  }

  private static ActionDefinition.Enabled parse(String content) throws InvalidActionFileException {
    ActionDefinition action = parseAny(content);
    assertThat(action).isInstanceOf(ActionDefinition.Enabled.class);
    return (ActionDefinition.Enabled) action;
  }

  private static ActionDefinition parseAny(String content) throws InvalidActionFileException {
    return ActionFileParser.parse("security", content.getBytes(UTF_8));
  }

  private static InvalidActionFileException parseError(String content) {
    return assertThrows(
        InvalidActionFileException.class,
        () -> ActionFileParser.parse("security", content.getBytes(UTF_8)));
  }

  private static void assertInvalid(String id, String content, String message) {
    InvalidActionFileException e =
        assertThrows(
            InvalidActionFileException.class,
            () -> ActionFileParser.parse(id, content.getBytes(UTF_8)));
    assertThat(e).hasMessageThat().contains(message);
  }
}
