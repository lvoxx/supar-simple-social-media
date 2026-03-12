# user-service  ·  port 8081

Manages user profiles, the social graph (follow/unfollow), follow requests, and account history.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/users/me` | Own profile |
| `GET` | `/api/v1/users/{username}` | Public profile by username |
| `GET` | `/api/v1/users/search?q=` | Prefix user search |
| `PUT` | `/api/v1/users/me` | Update profile (partial) |
| `PUT` | `/api/v1/users/me/avatar` | Replace avatar (requires READY media asset) |
| `PUT` | `/api/v1/users/me/background` | Replace banner image |
| `PUT` | `/api/v1/users/me/settings` | Update notification/theme settings |
| `POST` | `/api/v1/users/{userId}/follow` | Follow user (creates request for private accounts) |
| `DELETE` | `/api/v1/users/{userId}/follow` | Unfollow user |
| `GET` | `/api/v1/users/{userId}/followers` | Cursor-paginated follower list |
| `GET` | `/api/v1/users/{userId}/following` | Cursor-paginated following list |
| `GET` | `/api/v1/users/me/follow-requests` | Pending incoming follow requests |
| `PUT` | `/api/v1/users/me/follow-requests/{reqId}` | Approve or reject follow request |
| `GET` | `/api/v1/users/me/history` | Account action history |
| `POST` | `/api/v1/users/me/verify` | Submit identity verification |

## Domain Events Published

| Topic | Trigger |
|-------|---------|
| `user.profile.updated` | Profile fields changed |
| `user.avatar.changed` | Avatar replaced |
| `user.background.changed` | Background image replaced |
| `user.followed` | Follow relationship created |
| `user.unfollowed` | Follow relationship removed |

## Datastore

- **PostgreSQL** — `users`, `followers`, `follow_requests`, `account_history` tables
- **Redis** — profile cache (`user:profile:<id>`, TTL 5 min), follower-count cache (TTL 1 min)

## Distributed Locking

`follow()` acquires `LockKeys.follow(followerId, targetId)` (500 ms wait, 5 s lease)
to prevent race conditions between concurrent follow/unfollow calls for the same pair.

## Swagger UI

`http://localhost:8081/swagger-ui.html`
