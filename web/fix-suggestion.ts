/**
 * @license
 * Copyright (C) 2026 GerritForge, Inc.
 *
 * Licensed under the BSL 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type {
  CommentRange,
  FixReplacementInfo,
} from '@gerritcodereview/typescript-api/rest-api';
import {FIX_COMMENT_PROMPT} from './prompts';

/** A text file split into lines, without line terminators. */
export interface FileLines {
  lines: string[];
  // Line terminator used by the file, applied to model-generated text.
  eol: '\n' | '\r\n';
}

export class InvalidFixError extends Error {}

export function splitLines(content: string): FileLines {
  const eol = content.includes('\r\n') ? '\r\n' : '\n';
  const lines = content.split(eol);
  // A terminating newline does not start a new line.
  if (lines.length > 1 && lines[lines.length - 1] === '') lines.pop();
  return {lines, eol};
}

function describeLocation(range?: CommentRange, lineNumber?: number): string {
  if (range && range.start_line !== range.end_line) {
    return `lines ${range.start_line}-${range.end_line}`;
  }
  const line = range?.start_line ?? lineNumber;
  return line ? `line ${line}` : 'the file';
}

export function buildFixPrompt(args: {
  filePath: string;
  file: FileLines;
  comment: string;
  range?: CommentRange;
  lineNumber?: number;
}): string {
  const numbered = args.file.lines
    .map((line, i) => `${i + 1}| ${line}`)
    .join('\n');
  // Replacer functions: string replacements would expand `$&` etc.
  return FIX_COMMENT_PROMPT.replace('{{file_path}}', () => args.filePath)
    .replace('{{location}}', () =>
      describeLocation(args.range, args.lineNumber),
    )
    .replace('{{comment}}', () => args.comment)
    .replace('{{file}}', () => numbered);
}

declare interface LineReplacement {
  start_line: number;
  end_line: number;
  replacement: string;
}

function isLineReplacement(value: unknown): value is LineReplacement {
  return (
    typeof value === 'object' &&
    value !== null &&
    'start_line' in value &&
    'end_line' in value &&
    'replacement' in value &&
    Number.isInteger(value.start_line) &&
    Number.isInteger(value.end_line) &&
    typeof value.replacement === 'string'
  );
}

function parseLineReplacements(text: string): LineReplacement[] {
  // Models often wrap JSON in Markdown fences or prose despite instructions.
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end < start) {
    throw new InvalidFixError('AI response contains no JSON object');
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text.slice(start, end + 1));
  } catch (e) {
    throw new InvalidFixError(`AI response is not valid JSON: ${e}`);
  }
  if (
    typeof parsed !== 'object' ||
    parsed === null ||
    !('replacements' in parsed) ||
    !Array.isArray(parsed.replacements) ||
    !parsed.replacements.every(isLineReplacement)
  ) {
    throw new InvalidFixError('AI response has no valid "replacements" list');
  }
  return parsed.replacements;
}

/**
 * Converts the model's whole-line replacements into Gerrit fix replacements.
 * Returns an empty list when the model proposes no change.
 */
export function toFixReplacements(
  aiResponse: string,
  filePath: string,
  file: FileLines,
): FixReplacementInfo[] {
  const {lines, eol} = file;
  const replacements = parseLineReplacements(aiResponse).sort(
    (a, b) => a.start_line - b.start_line,
  );

  let previousEnd = 0;
  return replacements.map(r => {
    if (
      r.start_line <= previousEnd ||
      r.end_line < r.start_line ||
      r.end_line > lines.length
    ) {
      throw new InvalidFixError(
        `Invalid or overlapping line range ${r.start_line}-${r.end_line}`,
      );
    }
    previousEnd = r.end_line;

    const text = r.replacement.replace(/\r?\n$/, '').replace(/\r?\n/g, eol);
    if (r.end_line < lines.length) {
      // Cover the line terminator too, so an empty replacement deletes the
      // lines instead of leaving them blank.
      return {
        path: filePath,
        range: {
          start_line: r.start_line,
          start_character: 0,
          end_line: r.end_line + 1,
          end_character: 0,
        },
        replacement: text === '' ? '' : text + eol,
      };
    }
    return {
      path: filePath,
      range: {
        start_line: r.start_line,
        start_character: 0,
        end_line: r.end_line,
        end_character: lines[r.end_line - 1].length,
      },
      replacement: text,
    };
  });
}
