"""AndroidOnly: WP-004 Bounded Swift lexer/declaration reader, never a regex test counter."""

import bisect
import re
from dataclasses import dataclass, field

from .reference import OracleError

IDENTIFIER = re.compile(r"[^\W\d]\w*|_\w*", re.UNICODE)
NUMBER = re.compile(r"0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?[\d_]+)?")
MODIFIERS = {
    "public", "private", "fileprivate", "internal", "open", "static", "class",
    "final", "override", "nonisolated", "mutating", "nonmutating", "required",
    "convenience", "indirect", "distributed", "isolated",
}
TYPE_KINDS = {"struct", "class", "enum", "actor", "extension", "protocol"}


@dataclass(frozen=True)
class Token:
    text: str
    kind: str
    start: int
    end: int
    line: int


def lex(text: str) -> list[Token]:
    lines = [-1] + [match.start() for match in re.finditer("\n", text)]
    result = []

    def comment(position):
        if text.startswith("//", position):
            end = text.find("\n", position)
            return len(text) if end < 0 else end
        depth, position = 1, position + 2
        while position < len(text) and depth:
            if text.startswith("/*", position):
                depth += 1
                position += 2
            elif text.startswith("*/", position):
                depth -= 1
                position += 2
            else:
                position += 1
        if depth:
            raise OracleError("Unterminated Swift block comment")
        return position

    def string(position):
        beginning = position
        while position < len(text) and text[position] == "#":
            position += 1
        hashes = text[beginning:position]
        quote = '"""' if text.startswith('"""', position) else '"'
        position += len(quote)
        closing, escape = quote + hashes, "\\" + hashes
        while position < len(text):
            if text.startswith(closing, position):
                return position + len(closing)
            if text.startswith(escape, position):
                position += len(escape)
                if text.startswith("(", position):
                    position = interpolation(position + 1)
                else:
                    position += 1
            else:
                position += 1
        raise OracleError("Unterminated Swift string literal")

    def interpolation(position):
        depth = 1
        while position < len(text):
            if text.startswith(("//", "/*"), position):
                position = comment(position)
            elif re.match(r'#*"', text[position:position + 32]):
                position = string(position)
            elif text[position] == "(":
                depth += 1
                position += 1
            elif text[position] == ")":
                depth -= 1
                position += 1
                if depth == 0:
                    return position
            else:
                position += 1
        raise OracleError("Unterminated Swift string interpolation")

    position = 0
    while position < len(text):
        char, start = text[position], position
        if char.isspace():
            position += 1
            continue
        if text.startswith(("//", "/*"), position):
            position = comment(position)
            continue
        if re.match(r'#*"', text[position:position + 32]):
            position, kind = string(position), "string"
        elif char == "`":
            end = text.find("`", position + 1)
            if end < 0 or "\n" in text[position:end]:
                raise OracleError("Unterminated Swift escaped identifier")
            position, kind = end + 1, "identifier"
        elif match := IDENTIFIER.match(text, position):
            position, kind = match.end(), "identifier"
        elif match := NUMBER.match(text, position):
            position, kind = match.end(), "number"
        else:
            operator = next((op for op in ("...", "..<", "->", "==", "!=", "<=", ">=", "??", "&&", "||")
                             if text.startswith(op, position)), char)
            position, kind = position + len(operator), "symbol"
        result.append(Token(text[start:position], kind, start, position, bisect.bisect_left(lines, start)))
    return result


def canonical(tokens) -> str:
    return " ".join(token.text for token in tokens)


def identifier(token: Token) -> str:
    if token.kind != "identifier":
        raise OracleError(f"Expected Swift identifier at line {token.line}, got {token.text!r}")
    return token.text.strip("`")


def literal_string(token: Token) -> str:
    if token.kind != "string":
        raise OracleError("Expected a literal Swift display name")
    raw = token.text
    hashes = len(raw) - len(raw.lstrip("#"))
    quote = '"""' if raw[hashes:].startswith('"""') else '"'
    body = raw[hashes + len(quote):-(len(quote) + hashes)]
    escape = "\\" + "#" * hashes
    if escape + "(" in body:
        raise OracleError("Interpolated display names require explicit inventory support")
    replacements = {"0": "\0", "n": "\n", "r": "\r", "t": "\t", '"': '"', "'": "'", "\\": "\\"}

    def unescape(match):
        value = match.group(1)
        if value.startswith("u{"):
            try:
                return chr(int(value[2:-1], 16))
            except (ValueError, OverflowError) as error:
                raise OracleError("Invalid Swift Unicode escape") from error
        if value not in replacements:
            raise OracleError(f"Unsupported Swift string escape: {value}")
        return replacements[value]

    return re.sub(re.escape(escape) + r"(u\{[0-9a-fA-F]+\}|.)", unescape, body)


class Syntax:
    def __init__(self, text: str):
        self.text, self.tokens = text, lex(text)
        self.pairs = {}
        stack = []
        for index, token in enumerate(self.tokens):
            if token.text in ("(", "[", "{"):
                stack.append(index)
            elif token.text in (")", "]", "}"):
                if not stack or self.tokens[stack[-1]].text != {")": "(", "]": "[", "}": "{"}[token.text]:
                    raise OracleError(f"Unbalanced Swift delimiter at line {token.line}")
                begin = stack.pop()
                self.pairs[begin] = index
        if stack:
            raise OracleError(f"Unclosed Swift delimiter at line {self.tokens[stack[-1]].line}")

    def split(self, begin: int, end: int, separator=",") -> list[tuple[int, int]]:
        result, start, index = [], begin, begin
        while index < end:
            if self.tokens[index].text == separator:
                if start == index:
                    raise OracleError("Empty Swift argument")
                result.append((start, index))
                start = index + 1
            index = self.pairs.get(index, index) + 1
        if start < end:
            result.append((start, end))
        return result

    def raw(self, begin: int, end: int) -> str:
        return self.text[self.tokens[begin].start:self.tokens[end - 1].end] if begin < end else ""

    def body_after(self, begin: int, end: int) -> int | None:
        index = begin
        while index < end:
            value = self.tokens[index].text
            if value == "{":
                return index
            if value in ("func", "init", "deinit", "var", "let", *TYPE_KINDS):
                return None
            index = self.pairs.get(index, index) + 1
        return None


@dataclass
class Attribute:
    name: str
    begin: int
    end: int
    arguments: tuple[int, int]


@dataclass
class Declaration:
    kind: str
    name: str
    scope: tuple[str, ...]
    begin: int
    keyword: int
    end: int
    body: tuple[int, int] | None = None
    parameters: tuple[int, int] | None = None
    attributes: list[Attribute] = field(default_factory=list)
    xctest: bool = False
    parent: "Declaration | None" = None


def declarations(syntax: Syntax) -> list[Declaration]:
    tokens, result = syntax.tokens, []

    def walk(begin, end, scope=(), xctest=False, parent=None):
        index, pending, prefix = begin, [], None
        while index < end:
            token, value = tokens[index], tokens[index].text
            if value == "@":
                start, index = index, index + 1
                name = identifier(tokens[index])
                index += 1
                while index + 1 < end and tokens[index].text == ".":
                    name += "." + identifier(tokens[index + 1])
                    index += 2
                arguments = (index, index)
                if index < end and tokens[index].text == "(":
                    close = syntax.pairs[index]
                    arguments = (index + 1, close)
                    index = close + 1
                pending.append(Attribute(name, start, index, arguments))
                prefix = start if prefix is None else prefix
                continue
            if value in MODIFIERS and not (value == "class" and index + 1 < end and tokens[index + 1].text not in ("func", "var")):
                prefix = index if prefix is None else prefix
                index += 1
                if index < end and tokens[index].text == "(":
                    close = syntax.pairs[index]
                    is_setter_access = (value in ("public", "private", "fileprivate", "internal")
                                        and close == index + 2 and tokens[index + 1].text == "set")
                    if value == "nonisolated" or is_setter_access:
                        index = close + 1
                continue
            if value in TYPE_KINDS:
                start = prefix if prefix is not None else index
                name_end = index + 2
                if value == "extension":
                    name_end = index + 1
                    while name_end < end and tokens[name_end].text not in ("where", ":", "{"):
                        name_end = syntax.pairs.get(name_end, name_end) + 1
                    name = canonical(tokens[index + 1:name_end])
                    if not name:
                        raise OracleError("Missing Swift extension type")
                else:
                    name = identifier(tokens[index + 1])
                opening = syntax.body_after(name_end, end)
                if opening is None:
                    raise OracleError(f"Unsupported Swift type declaration at line {token.line}")
                close = syntax.pairs[opening]
                inherited_xctest = xctest or any(t.text == "XCTestCase" for t in tokens[name_end:opening])
                declaration = Declaration(value, name, scope, start, index, close + 1,
                                          (opening + 1, close), attributes=pending,
                                          xctest=inherited_xctest, parent=parent)
                if any(attr.name.split(".")[-1] == "Test" for attr in pending):
                    raise OracleError(f"@Test attached to a type at line {token.line}")
                result.append(declaration)
                walk(opening + 1, close, scope + (name,), inherited_xctest, declaration)
                index, pending, prefix = close + 1, [], None
                continue
            if value in ("func", "init", "deinit"):
                start = prefix if prefix is not None else index
                cursor = index + 1
                if value == "func":
                    if tokens[cursor].kind == "identifier":
                        name = identifier(tokens[cursor])
                        cursor += 1
                    else:
                        operator_begin = cursor
                        while cursor < end and tokens[cursor].kind == "symbol" and tokens[cursor].text != "(":
                            cursor += 1
                        name = "".join(t.text for t in tokens[operator_begin:cursor])
                        if not name:
                            raise OracleError(f"Unsupported Swift function name at line {token.line}")
                else:
                    name = value
                parameters = None
                if value != "deinit":
                    while cursor < end and tokens[cursor].text != "(":
                        cursor += 1
                    if cursor >= end:
                        raise OracleError(f"Missing Swift function parameters at line {token.line}")
                    parameters = (cursor + 1, syntax.pairs[cursor])
                    cursor = parameters[1] + 1
                opening = syntax.body_after(cursor, end)
                finish = syntax.pairs[opening] + 1 if opening is not None else cursor
                declaration = Declaration(value, name, scope, start, index, finish,
                                          (opening + 1, finish - 1) if opening is not None else None,
                                          parameters, pending, xctest, parent)
                result.append(declaration)
                index, pending, prefix = finish, [], None
                continue
            if value in ("let", "var"):
                if any(attr.name.split(".")[-1] in ("Test", "Suite") for attr in pending):
                    raise OracleError(f"Test/suite annotation attached to a property at line {token.line}")
                start = prefix if prefix is not None else index
                name = identifier(tokens[index + 1])
                cursor, opening, initialized = index + 2, None, False
                while cursor < end:
                    current = tokens[cursor]
                    if current.text == "=":
                        initialized = True
                    if current.text == "{":
                        if not initialized:
                            opening = cursor
                        cursor = syntax.pairs[cursor] + 1
                        if opening is not None:
                            break
                        continue
                    if current.text in ("(", "["):
                        cursor = syntax.pairs[cursor] + 1
                        continue
                    if current.text == ";" or (
                        current.line > tokens[cursor - 1].line
                        and tokens[cursor - 1].text not in ("=", ".", ",", "+", "-", "??", "->")
                        and current.text not in (".", "+", "-", "??")
                    ):
                        break
                    cursor += 1
                result.append(Declaration(value, name, scope, start, index, cursor,
                                          (opening + 1, syntax.pairs[opening]) if opening is not None else None,
                                          attributes=pending, parent=parent))
                index, pending, prefix = max(cursor, index + 2), [], None
                continue
            if any(attr.name.split(".")[-1] in ("Test", "Suite") for attr in pending) and value not in ("#", "if", "else", "endif"):
                raise OracleError(f"Unconsumed test/suite annotation at line {token.line}")
            if value in ("(", "[", "{"):
                index = syntax.pairs[index] + 1
            else:
                index += 1
            pending, prefix = [], None
        if any(attr.name.split(".")[-1] in ("Test", "Suite") for attr in pending):
            raise OracleError("Unconsumed test/suite annotation at end of scope")

    walk(0, len(tokens))
    return result


def assertion_sites(tokens: list[Token]) -> list[dict]:
    sites = []
    for index, token in enumerate(tokens):
        kind = None
        if token.text == "#" and index + 1 < len(tokens) and tokens[index + 1].text in ("expect", "require"):
            kind = "#" + tokens[index + 1].text
        elif token.kind == "identifier" and (token.text.startswith("XCTAssert") or token.text in ("XCTFail", "assert", "precondition", "assertionFailure")):
            if index + 1 < len(tokens) and tokens[index + 1].text == "(":
                kind = token.text
        elif token.text == "Issue" and [t.text for t in tokens[index + 1:index + 3]] == [".", "record"]:
            kind = "Issue.record"
        if kind:
            sites.append({"kind": kind, "line": token.line})
    return sites
