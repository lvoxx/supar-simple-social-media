# group-service

**Type:** Spring Boot · Port `8087`  
**Primary DB:** PostgreSQL (R2DBC) — schema `sssm_groups`  
**Cache:** Redis  
**Starters:** `postgres-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter`

---

## Responsibilities

Social group management: creation, membership lifecycle, role hierarchy, join policies, anti-bot screening, content pinning, tags, and group settings. **Posts inside a group are created via post-service** — group-service owns the group context and the group↔post association only.

---

## DB init

K8S `Job` runs Flyway CLI.  
Scripts: `infrastructure/k8s/db-init/group-service/sql/`

```
V1__init_groups.sql
V2__add_join_questions.sql
V3__add_invitations.sql
V4__add_activity_log.sql
V5__add_post_associations.sql
```

---

## Group visibility modes

| Mode | Discoverable | Join |
|------|-------------|------|
| `PUBLIC` | Yes | Instant (or queue if `autoApprove=false`) |
| `PRIVATE` | Yes | Request + optional screening questions |
| `INVITE_ONLY` | No | Invitation only |

---

## Role hierarchy & permissions

| Action | MEMBER | MODERATOR | ADMIN | OWNER |
|--------|:------:|:---------:|:-----:|:-----:|
| View group & members | ✅ | ✅ | ✅ | ✅ |
| Create post | ✅* | ✅ | ✅ | ✅ |
| Pin / unpin post | ❌ | ✅ | ✅ | ✅ |
| Approve join requests | ❌ | ✅ | ✅ | ✅ |
| Mute / remove member | ❌ | ✅ | ✅ | ✅ |
| Ban member | ❌ | ✅ | ✅ | ✅ |
| Update rules / policy | ❌ | ❌ | ✅ | ✅ |
| Promote to MODERATOR | ❌ | ❌ | ✅ | ✅ |
| Delete group | ❌ | ❌ | ❌ | ✅ |
| Transfer ownership | ❌ | ❌ | ❌ | ✅ |

\* Only when `policy.allowMemberPost = true`

---

## DB init

K8S `Job` runs Flyway CLI. No `flyway.*` config in application.yaml.

---

## Schema

No `REFERENCES` / `FOREIGN KEY`.

```sql
-- groups
id               UUID        PRIMARY KEY DEFAULT gen_random_uuid()
slug             VARCHAR(100) UNIQUE NOT NULL
name             VARCHAR(150) NOT NULL
description      TEXT
avatar_url       TEXT
background_url   TEXT
visibility       VARCHAR(20) NOT NULL DEFAULT 'PUBLIC'
status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
owner_id         UUID        NOT NULL
member_count     INT         NOT NULL DEFAULT 0
post_count       INT         NOT NULL DEFAULT 0
tags             TEXT[]
category         VARCHAR(60)
rules            JSONB                -- [{title, description, order}]
policy           JSONB                -- {autoApproveMembers, allowMemberPost, requireJoinAnswers,
                                     --  minAccountAgeDays, allowMemberInvite, postApproval, maxMembers}
settings         JSONB
created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at       TIMESTAMPTZ
created_by       UUID
updated_by       UUID
is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE
deleted_at       TIMESTAMPTZ
deleted_by       UUID

-- group_members
group_id         UUID        NOT NULL
user_id          UUID        NOT NULL
role             VARCHAR(20) NOT NULL DEFAULT 'MEMBER'
status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'  -- ACTIVE|BANNED|MUTED|PENDING_APPROVAL
join_answers     JSONB
invited_by       UUID
banned_by        UUID
ban_reason       TEXT
banned_at        TIMESTAMPTZ
joined_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at       TIMESTAMPTZ
PRIMARY KEY (group_id, user_id)

-- group_join_questions
id               UUID        PRIMARY KEY DEFAULT gen_random_uuid()
group_id         UUID        NOT NULL
question         TEXT        NOT NULL
order_index      INT         NOT NULL
is_required      BOOLEAN     NOT NULL DEFAULT TRUE
created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()

-- group_join_requests
id               UUID        PRIMARY KEY DEFAULT gen_random_uuid()
group_id         UUID        NOT NULL
requester_id     UUID        NOT NULL
answers          JSONB
status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
reviewed_by      UUID
reviewed_at      TIMESTAMPTZ
reject_reason    TEXT
created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()

-- group_invitations
id               UUID        PRIMARY KEY DEFAULT gen_random_uuid()
group_id         UUID        NOT NULL
inviter_id       UUID        NOT NULL
invitee_id       UUID        NOT NULL
status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
expires_at       TIMESTAMPTZ
created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()

-- group_pinned_posts  (max 5 per group, enforced in application layer)
group_id         UUID        NOT NULL
post_id          UUID        NOT NULL
pinned_by        UUID        NOT NULL
order_index      INT         NOT NULL DEFAULT 0
pinned_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
PRIMARY KEY (group_id, post_id)

-- group_post_associations
group_id         UUID        NOT NULL
post_id          UUID        NOT NULL
posted_by        UUID        NOT NULL
status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
PRIMARY KEY (group_id, post_id)

-- group_member_activity  (audit log, append-only)
id               UUID        PRIMARY KEY DEFAULT gen_random_uuid()
group_id         UUID        NOT NULL
actor_id         UUID        NOT NULL
target_id        UUID
action           VARCHAR(50) NOT NULL  -- BAN|UNBAN|PROMOTE|DEMOTE|APPROVE|REJECT|REMOVE
detail           JSONB
created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

---

## Integration with post-service

```
Client: POST /api/v1/groups/{groupId}/posts
  → group-service validates member permission
  → forwards to post-service with header X-Group-Id
  → post-service creates post (post_type=ORIGINAL, group_id populated)
  → post-service publishes: post.created (with groupId)
  → group-service consumes: post.created → inserts group_post_associations
  → group-service publishes: group.post.created → search-service
```

---

## Kafka

### Published

| Topic | Consumers |
|-------|-----------|
| `group.created` | search-svc, user-analysis-svc |
| `group.updated` | search-svc |
| `group.deleted` | search-svc, private-message-svc |
| `group.member.joined` | notification-svc, user-analysis-svc |
| `group.member.left` | notification-svc, private-message-svc |
| `group.member.role.changed` | notification-svc |
| `group.member.banned` | notification-svc |
| `group.post.pinned` | notification-svc |
| `group.post.created` | search-svc |

### Consumed

| Topic | Action |
|-------|--------|
| `post.created` | Register group_post_association |
| `post.deleted` | Remove from group_pinned_posts |
| `user.profile.updated` | Invalidate member display cache |

---

## API

```
POST   /api/v1/groups
GET    /api/v1/groups/{groupId}
PUT    /api/v1/groups/{groupId}
DELETE /api/v1/groups/{groupId}
PUT    /api/v1/groups/{groupId}/avatar
PUT    /api/v1/groups/{groupId}/background
PUT    /api/v1/groups/{groupId}/rules
PUT    /api/v1/groups/{groupId}/policy
PUT    /api/v1/groups/{groupId}/transfer-ownership

GET    /api/v1/groups/{groupId}/members
PUT    /api/v1/groups/{groupId}/members/{userId}/role
DELETE /api/v1/groups/{groupId}/members/{userId}
POST   /api/v1/groups/{groupId}/members/{userId}/ban
DELETE /api/v1/groups/{groupId}/members/{userId}/ban
POST   /api/v1/groups/{groupId}/members/{userId}/mute

POST   /api/v1/groups/{groupId}/join
POST   /api/v1/groups/{groupId}/leave
GET    /api/v1/groups/{groupId}/join-requests
PUT    /api/v1/groups/{groupId}/join-requests/{reqId}
POST   /api/v1/groups/{groupId}/invite
GET    /api/v1/groups/{groupId}/invitations
POST   /api/v1/groups/{groupId}/invitations/{invId}/respond

GET    /api/v1/groups/{groupId}/posts
POST   /api/v1/groups/{groupId}/posts
GET    /api/v1/groups/{groupId}/posts/pinned
POST   /api/v1/groups/{groupId}/posts/{postId}/pin
DELETE /api/v1/groups/{groupId}/posts/{postId}/pin

GET    /api/v1/groups/{groupId}/join-questions
PUT    /api/v1/groups/{groupId}/join-questions
GET    /api/v1/groups/{groupId}/activity-log

GET    /api/v1/groups/me/joined
GET    /api/v1/groups/me/owned
GET    /api/v1/groups/search?q=&tags=&category=
```

---

## Cache keys

| Key | TTL |
|-----|-----|
| `group:detail:{groupId}` | 5 min |
| `group:members:count:{groupId}` | 1 min |
| `group:pinned:{groupId}` | 2 min |
| `group:membership:{userId}:{groupId}` | 5 min |
| `group:policy:{groupId}` | 10 min |

---

## Rate limits

| Endpoint | Limit |
|----------|-------|
| `POST /groups` | 3/day per userId |
| `POST /join` | 5/hour per userId |
| `POST /invite` | 20/day per userId |

---

## Tests

- **Unit:** `GroupServiceTest`, `MembershipServiceTest`, `JoinRequestServiceTest`
- **Integration:** PostgreSQL + Redis + Kafka containers
- **Automation:** create → join (private) → approve → pin post → transfer ownership → ban member
