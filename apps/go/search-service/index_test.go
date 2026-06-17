package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"google.golang.org/protobuf/encoding/protowire"
)

// appendString encodes a length-delimited (string) field. Shared with consumer_test.go for building
// event bodies that exercise routing through eventv1.
func appendString(b []byte, field protowire.Number, s string) []byte {
	b = protowire.AppendTag(b, field, protowire.BytesType)
	return protowire.AppendString(b, s)
}

// captured records the requests the osIndex client makes so we can assert method, path, and body.
type captured struct {
	method string
	path   string
	body   map[string]any
}

func newTestServer(t *testing.T, status int, respBody string, sink *[]captured) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c := captured{method: r.Method, path: r.URL.Path}
		if raw, _ := io.ReadAll(r.Body); len(raw) > 0 {
			_ = json.Unmarshal(raw, &c.body)
		}
		*sink = append(*sink, c)
		w.WriteHeader(status)
		io.WriteString(w, respBody)
	}))
}

func TestIndexPostPutsDocByID(t *testing.T) {
	var reqs []captured
	srv := newTestServer(t, http.StatusOK, `{"result":"created"}`, &reqs)
	defer srv.Close()
	idx := newOSIndex(srv.URL, "posts", srv.Client())

	err := idx.IndexPost(context.Background(), PostDoc{PostID: "p1", Text: "hi"})
	if err != nil {
		t.Fatalf("IndexPost: %v", err)
	}
	if len(reqs) != 1 || reqs[0].method != http.MethodPut || reqs[0].path != "/posts/_doc/p1" {
		t.Fatalf("unexpected request: %+v", reqs)
	}
	if reqs[0].body["text"] != "hi" {
		t.Fatalf("body not sent: %+v", reqs[0].body)
	}
}

func TestDeletePostSwallows404(t *testing.T) {
	var reqs []captured
	srv := newTestServer(t, http.StatusNotFound, `{"result":"not_found"}`, &reqs)
	defer srv.Close()
	idx := newOSIndex(srv.URL, "posts", srv.Client())

	if err := idx.DeletePost(context.Background(), "missing"); err != nil {
		t.Fatalf("404 on delete should be a no-op, got: %v", err)
	}
	if reqs[0].method != http.MethodDelete || reqs[0].path != "/posts/_doc/missing" {
		t.Fatalf("unexpected request: %+v", reqs)
	}
}

func TestSearchBuildsMatchQueryAndParsesHits(t *testing.T) {
	var reqs []captured
	body := `{"hits":{"total":{"value":2},"hits":[
		{"_score":1.5,"_source":{"post_id":"p1","author_id":"a1","text":"go rocks"}},
		{"_score":0.9,"_source":{"post_id":"p2","author_id":"a2","text":"more go"}}
	]}}`
	srv := newTestServer(t, http.StatusOK, body, &reqs)
	defer srv.Close()
	idx := newOSIndex(srv.URL, "posts", srv.Client())

	res, err := idx.Search(context.Background(), SearchQuery{Text: "go", AuthorID: "a1", Limit: 10, Offset: 5})
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if res.Total != 2 || len(res.Hits) != 2 {
		t.Fatalf("parsed result wrong: %+v", res)
	}
	if res.Hits[0].PostID != "p1" || res.Hits[0].Score != 1.5 {
		t.Fatalf("first hit wrong: %+v", res.Hits[0])
	}

	req := reqs[0]
	if req.method != http.MethodPost || req.path != "/posts/_search" {
		t.Fatalf("unexpected search request: %+v", req)
	}
	if req.body["from"].(float64) != 5 || req.body["size"].(float64) != 10 {
		t.Fatalf("from/size not sent: %+v", req.body)
	}
	// query.bool.must.match.text == "go"
	boolQ := req.body["query"].(map[string]any)["bool"].(map[string]any)
	match := boolQ["must"].(map[string]any)["match"].(map[string]any)
	if match["text"] != "go" {
		t.Fatalf("match query not built: %+v", boolQ)
	}
	if _, ok := boolQ["filter"]; !ok {
		t.Fatal("author filter missing")
	}
}

func TestSearchUsesMatchAllForEmptyText(t *testing.T) {
	var reqs []captured
	srv := newTestServer(t, http.StatusOK, `{"hits":{"total":{"value":0},"hits":[]}}`, &reqs)
	defer srv.Close()
	idx := newOSIndex(srv.URL, "posts", srv.Client())

	if _, err := idx.Search(context.Background(), SearchQuery{Text: "", Limit: 10}); err != nil {
		t.Fatalf("Search: %v", err)
	}
	boolQ := reqs[0].body["query"].(map[string]any)["bool"].(map[string]any)
	if _, ok := boolQ["must"].(map[string]any)["match_all"]; !ok {
		t.Fatalf("empty text should use match_all: %+v", boolQ)
	}
	if _, ok := boolQ["filter"]; ok {
		t.Fatal("no author filter expected when author empty")
	}
}

func TestSearchSurfacesServerError(t *testing.T) {
	var reqs []captured
	srv := newTestServer(t, http.StatusInternalServerError, `{"error":"boom"}`, &reqs)
	defer srv.Close()
	idx := newOSIndex(srv.URL, "posts", srv.Client())

	if _, err := idx.Search(context.Background(), SearchQuery{Text: "go", Limit: 10}); err == nil {
		t.Fatal("expected error on 500 response")
	}
}

func TestEnsureIndexSkipsWhenPresent(t *testing.T) {
	var reqs []captured
	srv := newTestServer(t, http.StatusOK, ``, &reqs)
	defer srv.Close()
	idx := newOSIndex(srv.URL, "posts", srv.Client())

	if err := idx.EnsureIndex(context.Background()); err != nil {
		t.Fatalf("EnsureIndex: %v", err)
	}
	// HEAD 200 => exists => no create PUT.
	if len(reqs) != 1 || reqs[0].method != http.MethodHead {
		t.Fatalf("should only HEAD when index exists: %+v", reqs)
	}
}
