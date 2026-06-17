package main

import "time"

// PostDoc is the indexed representation of a post. It is the OpenSearch _source for a document keyed
// by PostID. Only the fields needed to find and render a search hit are stored — search-service is a
// derived read model, not a system of record (post-service owns the canonical row).
type PostDoc struct {
	PostID        string    `json:"post_id"`
	AuthorID      string    `json:"author_id"`
	Text          string    `json:"text"`
	MediaIDs      []string  `json:"media_ids,omitempty"`
	ReplyToPostID string    `json:"reply_to_post_id,omitempty"`
	CreatedAt     time.Time `json:"created_at"`
}

// SearchQuery is a resolved, validated full-text query. Text is matched against the post body; an
// empty Text matches everything (recent-posts browse). AuthorID, when set, filters to one author.
type SearchQuery struct {
	Text     string
	AuthorID string
	Limit    int
	Offset   int
}

// SearchHit is one result: the stored document plus its relevance score.
type SearchHit struct {
	PostDoc
	Score float64 `json:"score"`
}

// SearchResult is the raw index response: the hits on this page and the total number of matches
// (used to decide whether another page exists).
type SearchResult struct {
	Total int
	Hits  []SearchHit
}

// SearchPage is the HTTP response shape. NextCursor is an opaque, offset-based token; it is empty on
// the last page.
type SearchPage struct {
	Items      []SearchHit `json:"items"`
	Total      int         `json:"total"`
	NextCursor string      `json:"next_cursor,omitempty"`
}
