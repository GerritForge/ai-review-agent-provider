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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses action files: a strict JSON object between two {@code ---} lines, followed by the prompt.
 *
 * <pre>
 * ---
 * {"displayText": "Security review", "contextLines": 20}
 * ---
 * Review this patch for security issues. {{patch}}
 * </pre>
 */
class ActionFileParser {
  static final String FOLDER_NAME = "ai-review";
  static final String FOLDER = FOLDER_NAME + "/";
  static final String EXTENSION = ".md";
  static final int DEFAULT_CONTEXT_LINES = 10;

  private static final int MAX_DISPLAY_TEXT_LENGTH = 200;
  private static final int MAX_CONTEXT_LINES = 1_000_000;
  private static final int MAX_SHOWN_LENGTH = 120;
  private static final String ENABLED = "enabled";
  private static final String DISPLAY_TEXT = "displayText";
  private static final String CONTEXT_LINES = "contextLines";
  private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
  private static final String FENCE = "---";
  private static final char BYTE_ORDER_MARK = '\uFEFF';

  static boolean isValidId(String id) {
    return ID.matcher(id).matches();
  }

  /** A parsed file and the header fields this plugin version ignores, in file order. */
  record Parsed(ActionDefinition definition, ImmutableSet<String> unknownFields) {}

  static ActionDefinition parse(String id, byte[] content) throws InvalidActionFileException {
    return parseWithUnknownFields(id, content).definition();
  }

  /** Like {@link #parse}, and also reports unknown header fields, which are often typos. */
  static Parsed parseWithUnknownFields(String id, byte[] content)
      throws InvalidActionFileException {
    if (!isValidId(id)) {
      throw new InvalidActionFileException(
          String.format("file path must match %s%s%s", FOLDER, ID.pattern(), EXTENSION));
    }
    String text = decode(content);
    int fenceStart = !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? 1 : 0;
    int firstLineEnd = lineEnd(text, fenceStart);
    if (!line(text, fenceStart, firstLineEnd).equals(FENCE)) {
      throw new InvalidActionFileException("file must start with a '---' line");
    }
    int headerStart = nextLineStart(text, firstLineEnd);
    int lineStart = headerStart;
    while (lineStart < text.length()) {
      int end = lineEnd(text, lineStart);
      if (line(text, lineStart, end).equals(FENCE)) {
        Header header = parseHeader(text.substring(headerStart, lineStart));
        String body = text.substring(nextLineStart(text, end));
        return new Parsed(
            toDefinition(id, header, body), ImmutableSet.copyOf(header.unknownFields));
      }
      lineStart = nextLineStart(text, end);
    }
    throw new InvalidActionFileException("header must be closed by a '---' line");
  }

  private static ActionDefinition toDefinition(String id, Header header, String body)
      throws InvalidActionFileException {
    if (!header.enabled) {
      return new ActionDefinition.Disabled(id);
    }
    if (header.displayText == null || header.displayText.isBlank()) {
      throw new InvalidActionFileException(
          String.format("\"%s\" is required and must not be blank", DISPLAY_TEXT));
    }
    if (header.displayText.codePointCount(0, header.displayText.length())
        > MAX_DISPLAY_TEXT_LENGTH) {
      throw new InvalidActionFileException(
          String.format(
              "\"%s\" must not be longer than %d characters",
              DISPLAY_TEXT, MAX_DISPLAY_TEXT_LENGTH));
    }
    if (header.displayText.codePoints().anyMatch(ActionFileParser::isInvisible)) {
      throw new InvalidActionFileException(
          String.format("\"%s\" must not contain control or formatting characters", DISPLAY_TEXT));
    }
    if (body.isBlank()) {
      throw new InvalidActionFileException("prompt after the header must not be blank");
    }
    return new ActionDefinition.Enabled(id, header.displayText, header.contextLines, body);
  }

  private static Header parseHeader(String json) throws InvalidActionFileException {
    Header header = new Header();
    Set<String> seen = new HashSet<>();
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        throw new InvalidActionFileException("header must be a JSON object");
      }
      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (!seen.add(name)) {
          throw new InvalidActionFileException(
              String.format("header has duplicate field \"%s\"", shown(name)));
        }
        switch (name) {
          case ENABLED -> header.enabled = readBoolean(reader, name);
          case DISPLAY_TEXT -> header.displayText = readString(reader, name);
          case CONTEXT_LINES -> header.contextLines = readContextLines(reader);
          default -> {
            header.unknownFields.add(shown(name));
            reader.skipValue();
          }
        }
      }
      reader.endObject();
      if (!atEnd(reader)) {
        throw new InvalidActionFileException("header must contain a single JSON object");
      }
    } catch (IOException | IllegalStateException e) {
      throw new InvalidActionFileException("header is not valid JSON: " + shown(jsonError(e)));
    }
    return header;
  }

  private static boolean atEnd(JsonReader reader) {
    try {
      return reader.peek() == JsonToken.END_DOCUMENT;
    } catch (IOException e) {
      return false;
    }
  }

  /** Drops Gson's advice to developers, which means nothing to whoever wrote the file. */
  private static String jsonError(Exception e) {
    String message = Strings.nullToEmpty(e.getMessage());
    int seeAlso = message.indexOf("\nSee ");
    if (seeAlso >= 0) {
      message = message.substring(0, seeAlso);
    }
    return message.replace(
        "Use JsonReader.setStrictness(Strictness.LENIENT) to accept malformed JSON",
        "malformed JSON");
  }

  private static boolean readBoolean(JsonReader reader, String name)
      throws IOException, InvalidActionFileException {
    if (reader.peek() != JsonToken.BOOLEAN) {
      throw new InvalidActionFileException(String.format("\"%s\" must be true or false", name));
    }
    return reader.nextBoolean();
  }

  private static String readString(JsonReader reader, String name)
      throws IOException, InvalidActionFileException {
    if (reader.peek() != JsonToken.STRING) {
      throw new InvalidActionFileException(String.format("\"%s\" must be a string", name));
    }
    return reader.nextString();
  }

  private static int readContextLines(JsonReader reader)
      throws IOException, InvalidActionFileException {
    Integer lines = reader.peek() == JsonToken.NUMBER ? Ints.tryParse(reader.nextString()) : null;
    if (lines == null || lines < 0 || lines > MAX_CONTEXT_LINES) {
      throw new InvalidActionFileException(
          String.format(
              "\"%s\" must be an integer from 0 to %d", CONTEXT_LINES, MAX_CONTEXT_LINES));
    }
    return lines;
  }

  /** Line breaks, lone surrogates and bidi or zero-width formatting, which could disguise text. */
  private static boolean isInvisible(int codePoint) {
    return switch (Character.getType(codePoint)) {
      case Character.CONTROL,
              Character.FORMAT,
              Character.LINE_SEPARATOR,
              Character.PARAGRAPH_SEPARATOR,
              Character.SURROGATE ->
          true;
      default -> false;
    };
  }

  /** Text from the file as errors show it: on one line and shortened. */
  private static String shown(String text) {
    StringBuilder shown = new StringBuilder();
    text.codePoints()
        .limit(MAX_SHOWN_LENGTH)
        .forEach(c -> shown.appendCodePoint(isInvisible(c) ? '?' : c));
    if (text.codePointCount(0, text.length()) > MAX_SHOWN_LENGTH) {
      shown.append("...");
    }
    return shown.toString();
  }

  private static String decode(byte[] content) throws InvalidActionFileException {
    try {
      return UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content))
          .toString();
    } catch (CharacterCodingException e) {
      throw new InvalidActionFileException("file is not valid UTF-8");
    }
  }

  private static int lineEnd(String text, int start) {
    int newline = text.indexOf('\n', start);
    return newline < 0 ? text.length() : newline;
  }

  private static int nextLineStart(String text, int lineEnd) {
    return Math.min(lineEnd + 1, text.length());
  }

  /** Returns the line without its line break and trailing white space. */
  private static String line(String text, int start, int end) {
    return text.substring(start, end).stripTrailing();
  }

  private static class Header {
    boolean enabled = true;
    String displayText;
    int contextLines = DEFAULT_CONTEXT_LINES;
    Set<String> unknownFields = new LinkedHashSet<>();
  }

  private ActionFileParser() {}
}
