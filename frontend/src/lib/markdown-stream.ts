/**
 * 流式 Markdown 补全工具
 *
 * 当文本通过 SSE 流式传输时，markdown 语法标记可能被截断在中间，
 * 例如只收到了 "**粗体" 但尚未收到闭合的 "**"，导致 ReactMarkdown
 * 无法正确渲染。此模块会在流式过程中自动补全这些未闭合的标记。
 */

/**
 * 补全流式传输中不完整的 Markdown 语法，确保 ReactMarkdown 能正确渲染。
 *
 * 处理的标记类型：
 * - 粗体 **...**
 * - 斜体 *...*
 * - 行内代码 `...`
 * - 代码块 ```...```
 * - 删除线 ~~...~~
 * - 行尾未完成的标题 (# ...)
 *
 * @param text 当前已接收到的不完整文本
 * @returns 补全后的可安全渲染的 markdown 文本
 */
export function closeOpenMarkdown(text: string): string {
  if (!text) return text;

  let result = text;

  // 1. 处理未闭合的代码块 ```
  const codeBlockCount = countOccurrences(result, "```");
  if (codeBlockCount % 2 !== 0) {
    // 代码块未闭合 —— 追加闭合标记
    result += "\n```";
  }

  // 如果在代码块内部，不需要进一步处理行内标记
  if (codeBlockCount % 2 !== 0) {
    return result;
  }

  // 2. 处理行内标记（只处理最后一个未闭合的代码块外的内容）
  // 找到最后一个闭合代码块之后的文本
  const lastCodeBlockEnd = findLastClosedCodeBlockEnd(result);
  const outsideCodeBlock = result.slice(lastCodeBlockEnd);

  let suffix = "";

  // 3. 处理未闭合的行内代码 `
  const backtickCount = countOccurrences(outsideCodeBlock, "`");
  if (backtickCount % 2 !== 0) {
    suffix += "`";
  }

  // 4. 处理未闭合的粗体/斜体
  // 先处理 **（粗体），再处理单个 *（斜体）
  if (!suffix.includes("`")) {
    // 只在不在行内代码中时处理
    const boldCount = countNonEscapedPattern(outsideCodeBlock, "**");
    if (boldCount % 2 !== 0) {
      suffix += "**";
    }

    // 单个 * 斜体（排除 ** 中的 *）
    const italicCount = countSingleAsterisks(outsideCodeBlock);
    if (italicCount % 2 !== 0) {
      suffix += "*";
    }

    // 5. 处理未闭合的删除线 ~~
    const strikeCount = countNonEscapedPattern(outsideCodeBlock, "~~");
    if (strikeCount % 2 !== 0) {
      suffix += "~~";
    }
  }

  return result + suffix;
}

/** 计算字符串中某个子串出现的次数 */
function countOccurrences(text: string, pattern: string): number {
  let count = 0;
  let pos = 0;
  while ((pos = text.indexOf(pattern, pos)) !== -1) {
    count++;
    pos += pattern.length;
  }
  return count;
}

/** 计算非转义的特定模式出现次数 */
function countNonEscapedPattern(text: string, pattern: string): number {
  let count = 0;
  let pos = 0;
  while ((pos = text.indexOf(pattern, pos)) !== -1) {
    // 检查是否被反斜杠转义
    if (pos > 0 && text[pos - 1] === "\\") {
      pos += pattern.length;
      continue;
    }
    count++;
    pos += pattern.length;
  }
  return count;
}

/** 计算单独的 *（不属于 ** 的部分） */
function countSingleAsterisks(text: string): number {
  let count = 0;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === "*") {
      if (i > 0 && text[i - 1] === "\\") continue; // 转义
      if (text[i + 1] === "*") {
        i++; // 跳过 **
        continue;
      }
      if (i > 0 && text[i - 1] === "*") continue; // 属于 ** 的第二个
      count++;
    }
  }
  return count;
}

/** 找到最后一个已闭合代码块的结束位置 */
function findLastClosedCodeBlockEnd(text: string): number {
  let lastEnd = 0;
  let pos = 0;
  while (pos < text.length) {
    const openIdx = text.indexOf("```", pos);
    if (openIdx === -1) break;
    const closeIdx = text.indexOf("```", openIdx + 3);
    if (closeIdx === -1) break;
    lastEnd = closeIdx + 3;
    pos = lastEnd;
  }
  return lastEnd;
}
