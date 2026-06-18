package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// Index is the search backend search-service drives. An interface so both the consumer (writes) and
// the handler (reads) are unit-testable with a fake, never a live cluster — matching the rest of the
// fleet, which has no Docker/OpenSearch in the local toolchain.
type Index interface {
	EnsureIndex(ctx context.Context) error
	IndexPost(ctx context.Context, doc PostDoc) error
	DeletePost(ctx context.Context, postID string) error
	Search(ctx context.Context, q SearchQuery) (SearchResult, error)
}

// osIndex talks to OpenSearch over its REST API with the standard library only. The fleet favours a
// dependency-light approach (see common/eventv1's hand-written protowire decoder) over vendoring a
// large client for the handful of operations a derived read model needs: PUT/_doc, DELETE/_doc, and
// _search with a small query DSL.
type osIndex struct {
	baseURL string
	index   string
	http    *http.Client
}

func newOSIndex(baseURL, index string, hc *http.Client) *osIndex {
	if hc == nil {
		hc = http.DefaultClient
	}
	return &osIndex{baseURL: baseURL, index: index, http: hc}
}

// EnsureIndex creates the index with an explicit mapping if it does not already exist. text is a
// full-text field; the IDs are keywords (exact-match filter/term), created_at a date. Called once at
// startup; a concurrent create from another replica returns resource_already_exists, treated as ok.
func (o *osIndex) EnsureIndex(ctx context.Context) error {
	exists, err := o.indexExists(ctx)
	if err != nil {
		return err
	}
	if exists {
		return nil
	}
	body := map[string]any{
		"mappings": map[string]any{
			"properties": map[string]any{
				"post_id":          map[string]any{"type": "keyword"},
				"author_id":        map[string]any{"type": "keyword"},
				"text":             map[string]any{"type": "text"},
				"media_ids":        map[string]any{"type": "keyword"},
				"reply_to_post_id": map[string]any{"type": "keyword"},
				"created_at":       map[string]any{"type": "date"},
			},
		},
	}
	resp, err := o.do(ctx, http.MethodPut, "/"+o.index, body)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusBadRequest {
		// Most likely a race with another replica that created it first.
		if exists, _ := o.indexExists(ctx); exists {
			return nil
		}
	}
	return expectOK(resp, "create index")
}

func (o *osIndex) indexExists(ctx context.Context) (bool, error) {
	resp, err := o.do(ctx, http.MethodHead, "/"+o.index, nil)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)
	switch resp.StatusCode {
	case http.StatusOK:
		return true, nil
	case http.StatusNotFound:
		return false, nil
	default:
		return false, fmt.Errorf("head index: unexpected status %d", resp.StatusCode)
	}
}

// IndexPost upserts a document keyed by post id. PUT /_doc/{id} is idempotent under Kafka
// at-least-once redelivery: re-indexing the same PostCreated overwrites with identical content.
func (o *osIndex) IndexPost(ctx context.Context, doc PostDoc) error {
	resp, err := o.do(ctx, http.MethodPut, fmt.Sprintf("/%s/_doc/%s", o.index, doc.PostID), doc)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return expectOK(resp, "index post")
}

// DeletePost removes a document. A 404 means the post was never indexed (e.g. PostDeleted arrived
// before/without a PostCreated) and is treated as success — the desired end state already holds.
func (o *osIndex) DeletePost(ctx context.Context, postID string) error {
	resp, err := o.do(ctx, http.MethodDelete, fmt.Sprintf("/%s/_doc/%s", o.index, postID), nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil
	}
	return expectOK(resp, "delete post")
}

// Search runs a bool query: a match on text (or match_all when text is empty) plus an optional
// author_id term filter, paged with from/size and sorted by relevance then recency.
func (o *osIndex) Search(ctx context.Context, q SearchQuery) (SearchResult, error) {
	var must any
	if q.Text == "" {
		must = map[string]any{"match_all": map[string]any{}}
	} else {
		must = map[string]any{"match": map[string]any{"text": q.Text}}
	}
	boolQuery := map[string]any{"must": must}
	if q.AuthorID != "" {
		boolQuery["filter"] = map[string]any{"term": map[string]any{"author_id": q.AuthorID}}
	}
	body := map[string]any{
		"from":  q.Offset,
		"size":  q.Limit,
		"query": map[string]any{"bool": boolQuery},
		"sort": []any{
			"_score",
			map[string]any{"created_at": map[string]any{"order": "desc"}},
		},
	}

	resp, err := o.do(ctx, http.MethodPost, fmt.Sprintf("/%s/_search", o.index), body)
	if err != nil {
		return SearchResult{}, err
	}
	defer resp.Body.Close()
	// Check status inline rather than via expectOK, which drains the body on success — Search needs
	// to decode that body for the hits.
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return SearchResult{}, fmt.Errorf("search: opensearch returned %d: %s", resp.StatusCode, bytes.TrimSpace(snippet))
	}

	var raw struct {
		Hits struct {
			Total struct {
				Value int `json:"value"`
			} `json:"total"`
			Hits []struct {
				Score  float64 `json:"_score"`
				Source PostDoc `json:"_source"`
			} `json:"hits"`
		} `json:"hits"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return SearchResult{}, fmt.Errorf("decode search response: %w", err)
	}

	out := SearchResult{Total: raw.Hits.Total.Value}
	for _, h := range raw.Hits.Hits {
		out.Hits = append(out.Hits, SearchHit{PostDoc: h.Source, Score: h.Score})
	}
	return out, nil
}

func (o *osIndex) do(ctx context.Context, method, path string, body any) (*http.Response, error) {
	var rdr io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rdr = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, o.baseURL+path, rdr)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return o.http.Do(req)
}

// expectOK treats any 2xx as success and turns everything else into an error carrying a snippet of
// the response body for diagnosis.
func expectOK(resp *http.Response, op string) error {
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		io.Copy(io.Discard, resp.Body)
		return nil
	}
	snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	return fmt.Errorf("%s: opensearch returned %d: %s", op, resp.StatusCode, bytes.TrimSpace(snippet))
}
