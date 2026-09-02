package com.ecommerce.cms

private val allowedTags=Regex("</?(p|br|strong|em|ul|ol|li|h1|h2|h3|a|img|span|div)(\\s[^>]*)?>",RegexOption.IGNORE_CASE)

fun sanitizeHtml(input:String):String {
    var value=input.replace(Regex("<\\s*script[^>]*>.*?<\\s*/\\s*script\\s*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)),"")
        .replace(Regex("on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",RegexOption.IGNORE_CASE),"")
        .replace(Regex("(href|src)\\s*=\\s*(\"|')\\s*javascript:[^\"']*(\"|')",RegexOption.IGNORE_CASE),"")
    return value.replace(Regex("<[^>]*>")) { match -> allowedTags.matchEntire(match.value)?.value ?: "" }.take(200_000)
}
