import 'dart:convert';

import 'package:http/http.dart' as http;

const _owner = 'ViscousPot';
const _repo = 'GitSync';

const _minErrorLength = 12;
const _maxPages = 3;
const _requestTimeout = Duration(seconds: 10);

class DuplicateIssue {
  final int number;
  final String title;
  final String htmlUrl;

  const DuplicateIssue({required this.number, required this.title, required this.htmlUrl});
}

final _positionalPatterns = <RegExp>[
  RegExp(
    r'[\[(]?\s*(?:at|on|near)?\s*\b(?:line|ln)\b\s*#?\s*\d+(?:\s*[,;]?\s*\b(?:col|column|char|character|offset|position|pos)\b\s*#?\s*\d+)?\s*[\])]?',
    caseSensitive: false,
  ),
  RegExp(r'[\[(]?\s*(?:at|on|near)?\s*\b(?:col|column|char|character|offset|position|pos)\b\s*#?\s*\d+\s*[\])]?', caseSensitive: false),
  RegExp(r'\b0[xX][0-9a-fA-F]+\b'),
  RegExp(r':\d+(?::\d+)?\b'),
  RegExp(r'[\[(]\s*#?\d+\s*[\])]'),
  RegExp(r'\b\d{6,}\b'),
];

final _emptyBracketsPattern = RegExp(r'[\[(]\s*[\])]');

String normalizeErrorText(String raw) {
  var out = raw;
  for (final pattern in _positionalPatterns) {
    out = out.replaceAll(pattern, ' ');
  }
  out = out.replaceAll(_emptyBracketsPattern, ' ');
  return out.replaceAll(RegExp(r'\s+'), ' ').trim().toLowerCase();
}

Future<DuplicateIssue?> findDuplicateIssue(String token, String errorText) async {
  try {
    final target = normalizeErrorText(errorText);
    if (target.length < _minErrorLength) return null;

    DuplicateIssue? best;
    for (final issue in await _listOpenIssues(token)) {
      if (!normalizeErrorText(issue.title).contains(target)) continue;
      if (best == null || issue.number < best.number) best = issue;
    }

    return best;
  } catch (e) {
    print('[Duplicate Finder] $e');
    return null;
  }
}

Map<String, String> _headers(String token) => {'Authorization': 'token $token', 'Accept': 'application/vnd.github+json'};

Future<List<DuplicateIssue>> _listOpenIssues(String token) async {
  final issues = <DuplicateIssue>[];

  for (var page = 1; page <= _maxPages; page++) {
    final response = await http
        .get(Uri.parse('https://api.github.com/repos/$_owner/$_repo/issues?state=open&per_page=100&page=$page'), headers: _headers(token))
        .timeout(_requestTimeout);

    if (response.statusCode != 200) {
      print('[Duplicate Finder] list failed: ${response.statusCode}');
      break;
    }

    final items = jsonDecode(utf8.decode(response.bodyBytes)) as List<dynamic>;
    if (items.isEmpty) break;

    for (final item in items) {
      if (item is! Map || item['pull_request'] != null) continue;
      final number = item['number'] as int?;
      final title = item['title'] as String?;
      final htmlUrl = item['html_url'] as String?;
      if (number == null || title == null || htmlUrl == null) continue;
      issues.add(DuplicateIssue(number: number, title: title, htmlUrl: htmlUrl));
    }

    if (items.length < 100) break;
  }

  return issues;
}

Future<String?> postIssueComment(String token, int issueNumber, String body) async {
  try {
    final response = await http
        .post(
          Uri.parse('https://api.github.com/repos/$_owner/$_repo/issues/$issueNumber/comments'),
          headers: _headers(token),
          body: jsonEncode({'body': body}),
        )
        .timeout(_requestTimeout);

    if (response.statusCode != 201) {
      print('[Duplicate Finder] comment failed: ${response.statusCode} ${response.body}');
      return null;
    }

    final json = jsonDecode(utf8.decode(response.bodyBytes));
    return json['html_url'] as String?;
  } catch (e) {
    print('[Duplicate Finder] $e');
    return null;
  }
}
