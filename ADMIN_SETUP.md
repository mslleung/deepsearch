# DeepSearch Admin Application Setup

This guide covers how to run the DeepSearch admin backend and frontend applications.

## Admin Backend (Kotlin/Ktor)

The admin backend runs on **port 8081** and provides RESTful APIs for managing the DeepSearch system.

### Prerequisites

Before running the admin backend, ensure you have the following environment variables configured:

#### Required Environment Variables

1. **API_KEY_HMAC_SECRET** - For HMAC-SHA256 hashing of API keys
2. **DATABASE_ENCRYPTION_SECRET** - For AES-256-GCM encryption of sensitive database columns

Generate these secrets using OpenSSL:

```bash
# Generate API Key HMAC Secret (32-byte base64-encoded)
openssl rand -base64 32

# Generate Database Encryption Secret (32-byte base64-encoded)
openssl rand -base64 32
```

Set them as environment variables in your system or IDE run configuration:

```bash
API_KEY_HMAC_SECRET=<paste-your-generated-secret-here>
DATABASE_ENCRYPTION_SECRET=<paste-your-generated-secret-here>
```

**For development, you can use these test secrets:**
```bash
API_KEY_HMAC_SECRET=dev-hmac-secret-change-in-production-use-openssl-rand
DATABASE_ENCRYPTION_SECRET=dev-encryption-secret-change-in-production-use-openssl-rand
```

⚠️ **Important:** 
- Use strong, randomly generated secrets in production (use the openssl command above)
- Never commit these secrets to version control
- Use the same secrets across main and admin applications to access the same database

### Running the Admin Backend

#### Option 1: Using Gradle

```bash
cd D:/workspace/deepsearch
./gradlew :presentation:run -PmainClass=io.deepsearch.presentation.AdminApplicationKt
```

#### Option 2: Using IntelliJ IDEA

1. Open the `deepsearch` project in IntelliJ IDEA
2. Navigate to `presentation/src/main/kotlin/io/deepsearch/presentation/AdminApplication.kt`
3. Right-click on the `main` function and select "Run 'AdminApplicationKt'"

The admin backend will start on `http://localhost:8081`

### Admin API Endpoints

All endpoints are prefixed with `/admin` and do not require authentication:

#### Users
- `GET /admin/users` - List all users
- `GET /admin/users/{id}` - Get user by ID
- `PUT /admin/users/{id}` - Update user
- `DELETE /admin/users/{id}` - Delete user

#### Subscription Plans
- `GET /admin/subscription-plans` - List all plans
- `GET /admin/subscription-plans/{name}` - Get plan by name

#### User Subscriptions
- `GET /admin/user-subscriptions` - List all subscriptions (optional: `?userId={id}`)
- `GET /admin/user-subscriptions/{id}` - Get subscription by ID
- `POST /admin/user-subscriptions` - Create subscription
- `PUT /admin/user-subscriptions/{id}` - Update subscription
- `DELETE /admin/user-subscriptions/{id}` - Delete subscription

#### API Keys
- `GET /admin/api-keys` - List all API keys (optional: `?userId={id}`)
- `GET /admin/api-keys/{id}` - Get API key by ID
- `DELETE /admin/api-keys/{id}` - Revoke API key
- `GET /admin/api-keys/{id}/usage` - Get API key usage stats

#### Usage Analytics
- `GET /admin/usage` - Get aggregate usage stats (optional: `?days={days}`)
- `GET /admin/usage/users/{userId}` - Get user usage stats (optional: `?days={days}`)

#### Precache Jobs
- `GET /admin/precache` - List all precache jobs (optional: `?state={state}`)
- `GET /admin/precache/{id}` - Get precache job by ID
- `POST /admin/precache/{id}/stop` - Stop precache job

#### Cache
- `GET /admin/cache` - Get cache stats
- `POST /admin/cache/clear` - Clear cache

## Admin Frontend (Next.js)

The admin frontend runs on **port 3001** and provides a dashboard for managing the system.

### Prerequisites

- Node.js 18+ installed
- Admin backend running on port 8081

### Setup

```bash
cd D:/workspace/deepsearch-admin-web-app

# Install dependencies (first time only)
npm install

# Create .env.local file
echo "NEXT_PUBLIC_ADMIN_API_URL=http://localhost:8081" > .env.local
```

### Running the Admin Frontend

#### Development Mode

```bash
npm run dev
```

The admin dashboard will be available at `http://localhost:3001`

#### Production Build

```bash
npm run build
npm run start
```

### Admin Dashboard Features

- **Users**: View, edit, and delete users with their subscriptions and usage
- **Subscription Plans**: View available subscription plans
- **User Subscriptions**: Create, update, and manage user subscriptions
- **API Keys**: Monitor all API keys and revoke when needed
- **Usage Analytics**: View system-wide and per-user usage statistics with charts
- **Precache Jobs**: Monitor precache jobs progress and stop running jobs
- **Cache**: View cache statistics and manage cached content

## Configuration

### Backend Configuration

The admin backend is configured via `presentation/src/main/resources/admin-application.yaml`:

```yaml
ktor:
    development: true
    application:
        modules:
            - io.deepsearch.presentation.AdminApplicationKt.module
    deployment:
        port: 8081
```

### Frontend Configuration

The admin frontend is configured via `.env.local`:

```env
NEXT_PUBLIC_ADMIN_API_URL=http://localhost:8081
```

## CORS Configuration

The admin backend allows CORS requests from:
- `localhost:3001`
- `127.0.0.1:3001`

If you need to run the frontend on a different port, update the CORS configuration in `AdminApplication.kt`.

## Security Warning

⚠️ **IMPORTANT**: The admin application has **NO AUTHENTICATION** and is designed for internal use only.

- Never expose the admin backend (port 8081) to the public internet
- Never expose the admin frontend (port 3001) to the public internet
- Use network-level security (firewall, VPN, etc.) to restrict access
- Consider adding authentication if deploying in production

## Running Both Applications

### Option 1: Two Terminal Windows

Terminal 1 (Backend):
```bash
cd D:/workspace/deepsearch
./gradlew :presentation:run -PmainClass=io.deepsearch.presentation.AdminApplicationKt
```

Terminal 2 (Frontend):
```bash
cd D:/workspace/deepsearch-admin-web-app
npm run dev
```

### Option 2: Using IntelliJ + Terminal

1. Run admin backend from IntelliJ (see above)
2. Run frontend from terminal:
   ```bash
   cd D:/workspace/deepsearch-admin-web-app
   npm run dev
   ```

## Troubleshooting

### Backend Issues

**Port 8081 already in use:**
- Check if another instance is running: `netstat -ano | findstr :8081`
- Kill the process or change the port in `admin-application.yaml`

**Database connection errors:**
- Ensure the H2 database is initialized
- Check the main application has run at least once to create tables

### Frontend Issues

**Cannot connect to backend:**
- Verify the backend is running on port 8081
- Check the `NEXT_PUBLIC_ADMIN_API_URL` in `.env.local`
- Check browser console for CORS errors

**Module not found errors:**
- Run `npm install` to install dependencies
- Delete `node_modules` and `.next`, then run `npm install` again

**Port 3001 already in use:**
- Change the port in `package.json` scripts: `--port 3002`
- Update CORS in `AdminApplication.kt` if you change the port

## Development Tips

- Use React Query Devtools (included) to inspect API calls and cache
- Check Network tab in browser DevTools for API errors
- Backend logs will show in the terminal/IntelliJ console
- Frontend logs will show in both terminal and browser console

