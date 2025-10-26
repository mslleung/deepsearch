# DeepSearch Authentication & API Key System

## Overview

DeepSearch now features a comprehensive authentication and API key management system to productize the web search pipeline. The system uses:

- **JWT Authentication** for user management and dashboard access
- **API Key Authentication** for programmatic access to search/precache APIs
- **BCrypt** for secure password hashing
- **Exposed/R2DBC** for database operations (H2 for dev, PostgreSQL for production)

## Architecture

### Domain Layer

**New Entities:**
- `User`: Email, password (hashed), OAuth provider info, display name, timestamps
- `ApiKey`: User-owned keys with hash, prefix, name, usage tracking

**New Value Objects:**
- `Email`: Validated email addresses
- `PasswordHash`: BCrypt-hashed passwords
- `ApiKeyId`: Unique identifier for API keys
- `OAuthProvider`: Enum for Google, GitHub, Facebook

### Application Layer

**New Services:**
- `AuthService`: User registration, authentication, OAuth handling
- `ApiKeyService`: Generate, validate, list, delete API keys
- `JwtService`: Generate and validate JWT tokens
- `UserService`: Updated for new User model

### Infrastructure Layer

**Database Tables:**
- `users`: id, email (unique), password_hash, oauth_provider, oauth_provider_id, display_name, created_at, updated_at
- `api_keys`: id, user_id (FK), key_hash (unique), key_prefix, name, created_at, last_used_at, usage_count

### Presentation Layer

**New Endpoints:**

#### Authentication (`/api/auth`)
- `POST /api/auth/register` - Create account with email/password
- `POST /api/auth/login` - Login and receive JWT token
- `POST /api/auth/logout` - Logout (client-side token removal)
- `GET /api/auth/me` - Get current user (requires JWT)
- OAuth endpoints (planned): `/api/auth/oauth/{provider}`, `/api/auth/oauth/{provider}/callback`

#### API Keys (`/api/keys`)
- `GET /api/keys` - List user's API keys (requires JWT)
- `POST /api/keys` - Create new API key (requires JWT)
- `DELETE /api/keys/{id}` - Delete API key (requires JWT)

#### Protected Endpoints (require API key)
- `POST /api/search`
- `GET /api/cache/*`
- `GET /api/precache/*`
- `POST /api/precache/*`

## Authentication Flow

### User Registration/Login

```
1. User submits email/password → POST /api/auth/register or /login
2. Backend validates credentials
3. Backend generates JWT token (7-day expiration)
4. Frontend stores token in localStorage
5. Frontend includes token in all subsequent requests: Authorization: Bearer <JWT>
```

### API Key Creation

```
1. User authenticated with JWT → POST /api/keys with {name: "My Key"}
2. Backend generates random 32-char key: ds_live_<random>
3. Backend hashes key with BCrypt
4. Backend stores hash, returns full key (ONE TIME ONLY)
5. User copies key and uses for API requests
```

### API Key Usage

```
1. Client makes request: Authorization: Bearer ds_live_<key>
2. Backend hashes incoming key
3. Backend looks up hash in database
4. If found, updates last_used_at and usage_count
5. Request proceeds with authenticated user context
```

## Security Considerations

### Implemented
- ✅ BCrypt password hashing (12 rounds)
- ✅ API keys hashed in database
- ✅ JWT with expiration (7 days)
- ✅ Unique email enforcement
- ✅ Request-scoped repository/service lifecycle
- ✅ Separate auth flows for user vs API access

### Production Requirements
- ⚠️ **CRITICAL**: Set `JWT_SECRET` environment variable (strong random string)
- ⚠️ Use HTTPS in production (JWT/API keys transmitted in headers)
- ⚠️ Configure OAuth client secrets securely
- ⚠️ Implement rate limiting on auth endpoints
- ⚠️ Add CORS configuration
- ⚠️ Enable database connection pooling
- ⚠️ Rotate JWT secret periodically
- ⚠️ Add refresh token mechanism for JWT
- ⚠️ Implement API key expiration (currently unlimited)

## Configuration

### Environment Variables

**Backend (Ktor):**
```bash
# JWT Configuration (REQUIRED for production)
JWT_SECRET=<strong-random-string-min-32-chars>

# Database (for production)
DB_HOST=localhost
DB_PORT=5432
DB_NAME=deepsearch
DB_USERNAME=deepsearch
DB_PASSWORD=<secure-password>

# OAuth (when implementing)
GOOGLE_CLIENT_ID=<your-google-client-id>
GOOGLE_CLIENT_SECRET=<your-google-client-secret>
GITHUB_CLIENT_ID=<your-github-client-id>
GITHUB_CLIENT_SECRET=<your-github-client-secret>
FACEBOOK_CLIENT_ID=<your-facebook-client-id>
FACEBOOK_CLIENT_SECRET=<your-facebook-client-secret>
```

**Frontend (Next.js):**
```bash
NEXT_PUBLIC_API_URL=http://localhost:8080
```

### Application Configuration

Edit `deepsearch/presentation/src/main/resources/application.yaml`:

```yaml
ktor:
  deployment:
    port: 8080
    
jwt:
  secret: ${JWT_SECRET}  # Read from environment
  issuer: "deepsearch"
  audience: "deepsearch-users"
  realm: "DeepSearch API"
```

## API Key Format

- **Prefix**: `ds_live_` (indicates live/production key)
- **Length**: 40 characters total (8 prefix + 32 random)
- **Character Set**: A-Z, a-z, 0-9
- **Example**: `ds_live_Xk7mP3qR9sT2vY4wZ8aB1cD5eF6gH7iJ`

For future:
- Test keys: `ds_test_` prefix
- Restricted keys: `ds_restricted_` prefix with scope limitations

## Database Schema

### Users Table
```sql
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    oauth_provider VARCHAR(50),
    oauth_provider_id VARCHAR(255),
    display_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_oauth ON users(oauth_provider, oauth_provider_id);
```

### API Keys Table
```sql
CREATE TABLE api_keys (
    id INTEGER PRIMARY KEY AUTO_INCREMENT,
    user_id INTEGER NOT NULL,
    key_hash VARCHAR(255) UNIQUE NOT NULL,
    key_prefix VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    usage_count BIGINT DEFAULT 0,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_api_keys_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_user ON api_keys(user_id);
```

## Pay-As-You-Go Preparation

The system is designed to support future pay-as-you-go pricing:

### Already Implemented
- `usage_count` field tracks API key usage
- `last_used_at` tracks when keys were last used
- User-key relationship allows per-user billing
- API key authentication allows request attribution

### Future Implementation
```sql
-- Billing table (to be added)
CREATE TABLE billing_records (
    id INTEGER PRIMARY KEY AUTO_INCREMENT,
    user_id INTEGER NOT NULL,
    api_key_id INTEGER,
    operation_type VARCHAR(50), -- 'search', 'precache', 'cache'
    tokens_used INTEGER,
    cost_cents INTEGER,
    timestamp TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (api_key_id) REFERENCES api_keys(id)
);

-- User quotas table
CREATE TABLE user_quotas (
    user_id INTEGER PRIMARY KEY,
    monthly_quota_cents INTEGER DEFAULT 0,
    current_usage_cents INTEGER DEFAULT 0,
    reset_date DATE NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

### Billing Integration Points

In `ApiKeyService.validateApiKey()`:
```kotlin
// After validating key, track usage
val updatedKey = apiKey.incrementUsage(now)
apiKeyRepository.update(updatedKey)

// Track for billing (future)
// billingService.recordUsage(apiKey.userId, operation, tokenCount, cost)
```

In search/precache services:
```kotlin
// Calculate token usage and cost
val tokenCount = calculateTokenUsage(request)
val costCents = (tokenCount * COST_PER_TOKEN).toInt()

// Record billing event (future)
// billingService.recordUsage(userId, "search", tokenCount, costCents)
```

## Testing

### Manual Testing

**Register a user:**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123","displayName":"Test User"}'
```

**Login:**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}'
# Save the returned token
```

**Create API key:**
```bash
curl -X POST http://localhost:8080/api/keys \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -d '{"name":"My Test Key"}'
# Save the returned rawKey
```

**Use API key for search:**
```bash
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ds_live_<your_key>" \
  -d '{"url":"https://example.com","query":"What is this site about?"}'
```

### Integration Tests

Test files are located in:
- `deepsearch/application/src/test/kotlin/.../services/`
- `deepsearch/presentation/src/test/kotlin/.../`

Run tests:
```bash
cd deepsearch
./gradlew test
```

## Migration Notes

### Breaking Changes
- ⚠️ Old `User` entity (with `name` and `age`) is replaced
- ⚠️ Old user endpoints `/users` are disabled
- ⚠️ Search and precache endpoints now require API key authentication

### Migration Steps for Existing Data
If you have existing development data:

```sql
-- Backup old users table
CREATE TABLE users_backup AS SELECT * FROM users;

-- Drop old table
DROP TABLE users;

-- Recreate with new schema (handled by DatabaseConfig automatically)
-- Manually migrate data if needed (not recommended for dev)
```

For production launches, since the product hasn't launched yet, no backward compatibility is needed.

## OAuth Integration (Planned)

To complete OAuth implementation:

### Backend (Ktor)
1. Install OAuth plugin in `Application.kt`
2. Configure providers in `application.yaml`
3. Create OAuth callback handlers
4. Use `AuthService.findOrCreateOAuthUser()` in callbacks

### Frontend (Next.js + Better Auth)
1. Install Better Auth: `npm install better-auth`
2. Configure providers in `lib/auth.ts`
3. Add OAuth buttons to sign-in/sign-up pages
4. Handle OAuth callbacks

Example (planned):
```typescript
// lib/auth.ts
import { betterAuth } from 'better-auth';

export const auth = betterAuth({
  baseURL: process.env.NEXT_PUBLIC_API_URL,
  emailAndPassword: { enabled: true },
  socialProviders: {
    google: {
      clientId: process.env.GOOGLE_CLIENT_ID!,
      clientSecret: process.env.GOOGLE_CLIENT_SECRET!,
    },
    github: { /* ... */ },
    facebook: { /* ... */ },
  },
});
```

## Troubleshooting

### JWT Token Issues
- **"Invalid or expired token"**: Token may have expired (7 days). Login again.
- **"JWT secret not configured"**: Set `JWT_SECRET` environment variable.

### API Key Issues
- **"Invalid or missing API key"**: Check key format and ensure it starts with `ds_live_`
- **Key not working**: Ensure key was copied correctly when created (it's only shown once)
- **Performance issues**: API key validation requires BCrypt hashing (slow by design for security). Consider caching valid keys.

### Database Issues
- **Table not found**: Run the application once to auto-create tables via `DatabaseConfig`
- **Connection errors**: Check database URL and credentials
- **H2 file locks**: Stop all application instances before restarting

## Performance Optimization

### API Key Validation
Current implementation hashes incoming keys for every request. For high-traffic scenarios:

```kotlin
// Add caching layer (future)
private val validatedKeysCache = ConcurrentHashMap<String, Pair<ApiKey, Instant>>()

fun validateApiKey(rawKey: String): ApiKey? {
    // Check cache first (with TTL)
    val cached = validatedKeysCache[rawKey]
    if (cached != null && cached.second > Clock.System.now().minus(5.minutes)) {
        return cached.first
    }
    
    // If not cached or expired, validate normally
    val apiKey = // ... existing validation
    
    if (apiKey != null) {
        validatedKeysCache[rawKey] = apiKey to Clock.System.now()
    }
    
    return apiKey
}
```

### Database Connection Pooling
Configure HikariCP for production:
```kotlin
// In DatabaseConfig
implementation("com.zaxxer:HikariCP:5.0.1")

val config = HikariConfig().apply {
    jdbcUrl = "jdbc:postgresql://..."
    username = "..."
    password = "..."
    maximumPoolSize = 10
    minimumIdle = 5
}
val dataSource = HikariDataSource(config)
```

## Security Audit Checklist

Before production:
- [ ] `JWT_SECRET` is strong random string (min 32 chars)
- [ ] All secrets in environment variables (not in code)
- [ ] HTTPS enabled with valid certificate
- [ ] CORS configured appropriately
- [ ] Rate limiting on auth endpoints
- [ ] Input validation on all endpoints
- [ ] SQL injection prevented (Exposed handles this)
- [ ] Password requirements enforced (min 8 chars currently)
- [ ] API key prefix stored separately for fast lookups
- [ ] Database backups configured
- [ ] Logging excludes sensitive data (passwords, raw keys)
- [ ] Error messages don't leak system information

## Support

For issues or questions:
- Check backend logs: `./gradlew :presentation:run`
- Check frontend logs: Browser developer console
- Review this documentation
- Check main project README.md for general setup

