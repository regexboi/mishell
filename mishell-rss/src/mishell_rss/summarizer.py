from __future__ import annotations

import json
import re

from openai import OpenAI

from mishell_rss.feeds import ParsedFeedEntry

SUMMARY_JSON_SCHEMA = {
    "name": "watch_summaries",
    "schema": {
        "type": "object",
        "properties": {
            "summaries": {
                "type": "array",
                "items": {
                    "type": "string",
                    "minLength": 10,
                    "maxLength": 220,
                },
                "minItems": 1,
                "maxItems": 5,
            }
        },
        "required": ["summaries"],
        "additionalProperties": False,
    },
    "strict": True,
}

SYSTEM_PROMPT = (
    "You create concise watch-sized summaries for AI news readers. "
    "Return one sentence per summary and focus on intent, novelty, and why it matters. "
    "Generate multiple summaries only when the article clearly has multiple distinct takeaways. "
    "Avoid filler, hype, and vague wording."
)


class ArticleSummarizer:
    def __init__(self, api_key: str | None, model: str = "gpt-5-mini") -> None:
        self.model = model
        self.client = OpenAI(api_key=api_key) if api_key else None

    def summarize_article(self, article: ParsedFeedEntry) -> list[str]:
        if self.client is None:
            return [self._fallback_summary(article)]

        user_prompt = self._build_user_prompt(article)

        try:
            response = self.client.responses.create(
                model=self.model,
                input=[
                    {
                        "role": "system",
                        "content": [{"type": "input_text", "text": SYSTEM_PROMPT}],
                    },
                    {
                        "role": "user",
                        "content": [{"type": "input_text", "text": user_prompt}],
                    },
                ],
                text={"format": {"type": "json_schema", **SUMMARY_JSON_SCHEMA}},
            )
            payload = json.loads(response.output_text)
            summaries = [s.strip() for s in payload.get("summaries", []) if s and s.strip()]
            if summaries:
                return summaries
        except Exception:
            pass

        return [self._fallback_summary(article)]

    @staticmethod
    def _fallback_summary(article: ParsedFeedEntry) -> str:
        base = article.title.strip() or "New article"
        sentence = re.sub(r"\s+", " ", base)
        return sentence if sentence.endswith(".") else f"{sentence}."

    @staticmethod
    def _build_user_prompt(article: ParsedFeedEntry) -> str:
        chunks = [
            f"Source: {article.source_name}",
            f"Title: {article.title}",
            f"URL: {article.link}",
        ]
        if article.excerpt:
            chunks.append(f"Excerpt: {article.excerpt}")
        if article.content:
            chunks.append(f"Content: {article.content[:4000]}")

        chunks.append(
            "Return JSON matching the schema. "
            "Each summary must be one sentence and readable on a small watch screen."
        )
        return "\n".join(chunks)
