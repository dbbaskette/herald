import { describe, expect, it } from 'vitest'
import MarkdownIt from 'markdown-it'
import { markdown } from '@codemirror/lang-markdown'
import { EditorState } from '@codemirror/state'
import { MergeView } from '@codemirror/merge'

describe('upgraded editor and markdown dependencies', () => {
  it('parses markdown in a real CodeMirror state', () => {
    const state = EditorState.create({
      doc: '# Heading\n\n- one\n- two',
      extensions: [markdown()],
    })

    expect(state.doc.lines).toBe(4)
    expect(state.languageDataAt('commentTokens', 0)).toBeDefined()
  })

  it('keeps the MergeView constructor used by the diff editor', () => {
    expect(typeof MergeView).toBe('function')
  })

  it('renders links, lists, and escaped HTML with markdown-it', () => {
    const rendered = new MarkdownIt({ html: false, linkify: true }).render(
      '<script>alert(1)</script>\n\n- [Herald](https://example.com)',
    )

    expect(rendered).toContain('&lt;script&gt;alert(1)&lt;/script&gt;')
    expect(rendered).toContain('<ul>')
    expect(rendered).toContain('<a href="https://example.com">Herald</a>')
  })
})
