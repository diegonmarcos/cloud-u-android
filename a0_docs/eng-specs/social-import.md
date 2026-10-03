# SocialImport — behaviour spec (clean-room, #828)

Written from the callers (`ContactsBridge.onFilePicked`, `SocialStore`,
`contacts.html`) and from the publicly documented export formats only. The
previous implementation (ported from Fossify Contacts, GPL-3.0) was NOT
consulted for this spec or for the implementation that follows it.

## Interface

```kotlin
object SocialImport {
    data class Result(val source: String, val contacts: List<RawContact>)
    fun parse(text: String): Result?
}
```

* `parse` takes the whole export file as text (UTF-8, possibly with a BOM,
  possibly truncated by the caller's 20 MB cap) and is pure: no I/O, no
  Android APIs other than `org.json`.
* It returns `null` when the text is not a recognised export. It never
  throws for malformed input (callers also catch, but must not need to).
* A recognised export with zero usable rows returns a `Result` with an empty
  list: the caller replaces that source wholesale, so "now empty" is a
  legitimate state.
* `Result.source` is one of the source ids the UI knows: `linkedin`,
  `instagram`. Every `RawContact.source` equals `Result.source`.

## LinkedIn — `Connections.csv`

* Format: RFC 4180 CSV (comma, `"` quoting, `""` escape, quoted fields may
  span lines, CRLF or LF). LinkedIn prepends a few "Notes:" lines before the
  header; anything before the header row is ignored.
* Header row: the first row containing both `First Name` and `Last Name`
  (case-insensitive, trimmed). Columns are located by header name, never by
  position: `First Name`, `Last Name`, `URL`, `Email Address`, `Company`,
  `Position`. Missing optional columns read as blank.
* Per row: `name` = first + " " + last, trimmed and space-collapsed;
  `org` = Company; `title` = Position; `emails` = [Email Address] if
  non-blank; `urls` = [URL] if non-blank; `handles["linkedin"]` = the slug
  after `/in/` in URL, when present.
* Rows with a blank name AND no email AND no URL are dropped (blank lines,
  trailing junk).
* No header row found → `null`.

## Instagram — followers / following JSON

* Format: JSON. Either a top-level array of entries (`followers_1.json`) or
  an object whose array-valued members hold entries
  (`{"relationships_following": [...]}`, close friends, etc.).
* Entry: an object with `string_list_data: [{href, value, timestamp}]`
  and/or `title`. Username = first non-blank of `string_list_data[].value`,
  `title`, last path segment of `href`.
* Per username: `name` = username, `handles["instagram"]` = username,
  `urls` = [`https://www.instagram.com/<username>`].
* Duplicates (same username, case-insensitive; e.g. someone both following
  and followed in a merged file) collapse to one contact; first wins.
* JSON that parses but has no entry-shaped arrays anywhere → `null`.

## Detection

Strip a leading BOM and whitespace. `[` or `{` first → Instagram rules,
anything else → LinkedIn rules. Empty text → `null`.
