import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({ breaks: true, gfm: true })

/**
 * Render markdown to sanitized HTML. Safe for v-html.
 */
export function renderMarkdown(text: string): string {
  if (!text?.trim()) return ''
  const raw = marked.parse(text) as string
  return DOMPurify.sanitize(raw)
}
