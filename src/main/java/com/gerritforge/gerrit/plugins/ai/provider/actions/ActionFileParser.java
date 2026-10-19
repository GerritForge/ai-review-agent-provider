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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses action files: a strict JSON object between two {@code ---} lines, followed by the prompt.
 * HTML comments before the first {@code ---} line, such as a licence header, are skipped.
 *
 * <pre>
 * &lt;!-- SPDX-License-Identifier: Apache-2.0 --&gt;
 * ---
 * {"displayText": "Security review", "contextLines": 20}
 * ---
 * Review this patch for security issues. {{patch}}
 * </pre>
 */
public class ActionFileParser {
  public static final String FOLDER = "ai-review/";
  public static final String EXTENSION = ".md";
  public static final int MAX_FILE_BYTES = 64 * 1024;
  public static final int MAX_DISPLAY_TEXT_LENGTH = 200;
  public static final int DEFAULT_CONTEXT_LINES = 10;
  public static final int MAX_CONTEXT_LINES = 1_000_000;

  static final String ENABLED = "enabled";
  static final String DISPLAY_TEXT = "displayText";
  static final String CONTEXT_LINES = "contextLines";

  private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
  private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
  private static final String FENCE = "---";
  private static final String COMMENT_START = "<!--";
  private static final String COMMENT_END = "-->";
  private static final char BYTE_ORDER_MARK = '\uFEFF';

  /**
   * Returns the file name without extension when {@code path} is an action file, that is a {@code
   * .md} file directly in {@code ai-review/}. Every other path is ignored by this plugin version.
   */
  public static Optional<String> fileName(String path) {
    if (!path.startsWith(FOLDER) || !path.endsWith(EXTENSION)) {
      return Optional.empty();
    }
    String name = path.substring(FOLDER.length(), path.length() - EXTENSION.length());
    return name.contains("/") ? Optional.empty() : Optional.of(name);
  }

  public static boolean isValidId(String id) {
    return ID.matcher(id).matches();
  }

  public static ActionDefinition parse(String id, byte[] content)
      throws InvalidActionFileException {
    if (!isValidId(id)) {
      throw new InvalidActionFileException(
          String.format("file name must match %s%s%s", FOLDER, ID.pattern(), EXTENSION));
    }
    if (content.length > MAX_FILE_BYTES) {
      throw new InvalidActionFileException(
          String.format("file is larger than %d bytes", MAX_FILE_BYTES));
    }
    String text = decode(content);
    int fenceStart = skipLeadingComments(text);

    int firstLineEnd = lineEnd(text, fenceStart);
    if (!line(text, fenceStart, firstLineEnd).equals(FENCE)) {
      throw new InvalidActionFileException(
          "file must start with a '---' line, after optional <!-- --> comments");
    }
    int headerStart = nextLineStart(text, firstLineEnd);
    int lineStart = headerStart;
    while (lineStart < text.length()) {
      int end = lineEnd(text, lineStart);
      if (line(text, lineStart, end).equals(FENCE)) {
        Header header = parseHeader(text.substring(headerStart, lineStart));
        String body = text.substring(nextLineStart(text, end));
        return toDefinition(id, header, body);
      }
      lineStart = nextLineStart(text, end);
    }
    throw new InvalidActionFileException("header must be closed by a '---' line");
  }

  /** Returns the start of the first line that is neither blank nor part of an HTML comment. */
  private static int skipLeadingComments(String text) throws InvalidActionFileException {
    int pos = !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? 1 : 0;
    while (pos < text.length()) {
      int end = lineEnd(text, pos);
      String line = line(text, pos, end);
      if (line.isBlank()) {
        pos = nextLineStart(text, end);
      } else if (line.stripLeading().startsWith(COMMENT_START)) {
        int commentEnd = text.indexOf(COMMENT_END, text.indexOf(COMMENT_START, pos));
        if (commentEnd < 0) {
          throw new InvalidActionFileException("comment before the header is not closed by -->");
        }
        int afterComment = commentEnd + COMMENT_END.length();
        int afterLine = lineEnd(text, afterComment);
        if (!line(text, afterComment, afterLine).isBlank()) {
          throw new InvalidActionFileException("text after --> must start on a new line");
        }
        pos = nextLineStart(text, afterLine);
      } else {
        return pos;
      }
    }
    return pos;
  }

  private static ActionDefinition toDefinition(String id, Header header, String body)
      throws InvalidActionFileException {
    if (header.enabled) {
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
            String.format(
                "\"%s\" must not contain control or formatting characters", DISPLAY_TEXT));
      }
      if (body.isBlank()) {
        throw new InvalidActionFileException("prompt after the header must not be blank");
      }
    }
    return new ActionDefinition(
        id,
        header.enabled,
        header.displayText,
        header.contextLines,
        body,
        ImmutableSet.copyOf(header.unknownFields));
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
              String.format("header has duplicate field \"%s\"", name));
        }
        switch (name) {
          case ENABLED -> header.enabled = readBoolean(reader, name);
          case DISPLAY_TEXT -> header.displayText = readString(reader, name);
          case CONTEXT_LINES -> header.contextLines = readContextLines(reader);
          default -> {
            header.unknownFields.add(name);
            reader.skipValue();
          }
        }
      }
      reader.endObject();
      if (!atEnd(reader)) {
        throw new InvalidActionFileException("header must contain a single JSON object");
      }
    } catch (IOException | IllegalStateException e) {
      throw new InvalidActionFileException("header is not valid JSON: " + jsonError(e));
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
    String error =
        String.format("\"%s\" must be an integer from 0 to %d", CONTEXT_LINES, MAX_CONTEXT_LINES);
    if (reader.peek() != JsonToken.NUMBER) {
      throw new InvalidActionFileException(error);
    }
    String value = reader.nextString();
    if (!INTEGER.matcher(value).matches() || value.length() > 8) {
      throw new InvalidActionFileException(error);
    }
    int lines = Integer.parseInt(value);
    if (lines < 0 || lines > MAX_CONTEXT_LINES) {
      throw new InvalidActionFileException(error);
    }
    return lines;
  }

  /** Control characters and bidi or zero-width formatting, which could disguise a label. */
  private static boolean isInvisible(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.CONTROL || type == Character.FORMAT;
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
