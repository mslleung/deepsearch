# DeepSearch Quick Start Guide

## 🚀 Get Up and Running in 5 Minutes

This guide will get you from zero to searching websites with DeepSearch.

## Prerequisites

- Java 17+ (for backend)
- Node.js 18+ (for frontend)
- Google Gemini API Key ([Get one free here](https://aistudio.google.com/app/apikey))

## Step 1: Set Up Environment Variables

```bash
# Required: Google API key for search functionality
export GOOGLE_API_KEY="your-google-gemini-api-key"

# Required for production (optional for dev - has default)
export JWT_SECRET="your-super-secret-random-string-min-32-chars"
```

## Step 2: Start the Backend

```bash
cd deepsearch
./gradlew :presentation:run
```

The backend will start on `http://localhost:8080` and automatically:
- Create the H2 database
- Initialize all tables (users, api_keys, cache, etc.)
- Be ready to accept requests

## Step 3: Start the Frontend

```bash
cd deepsearch-web-app
npm install  # First time only
npm run dev
```

The frontend will start on `http://localhost:3000`

## Step 4: Create Your Account

1. Open http://localhost:3000
2. Click "Sign Up"
3. Enter your email and password
4. Click "Sign Up"
5. You'll be automatically logged in and redirected to the dashboard

## Step 5: Create an API Key

1. Click "API Keys" in the sidebar
2. Click "Create New Key"
3. Enter a name like "My First Key"
4. Click "Create"
5. **IMPORTANT**: Copy the full API key immediately - you won't see it again!
   - It will look like: `ds_live_abc123...`
   - Click the "Copy" button to copy it to clipboard

## Step 6: Try a Search

1. Click "Search" in the sidebar (default page)
2. Paste a website URL (e.g., `https://example.com`)
3. Enter a query (e.g., "What is this website about?")
4. Click "Search"
5. See the AI-generated answer, extracted content, and source URLs!

## 🎉 That's It!

You now have a fully functional AI-powered web search system.

## What's Next?

### Explore Features
- **API Keys**: Create multiple keys, track usage, delete old ones
- **Search**: Try different URLs and queries
- **Cache**: View cached website content (coming soon)
- **Precache**: Pre-cache websites for faster searches (coming soon)

### Test the API Directly

Use your API key to test the REST API:

```bash
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ds_live_YOUR_KEY_HERE" \
  -d '{
    "url": "https://example.com",
    "query": "What is this website about?"
  }'
```

### Production Deployment

See:
- `README-AUTH.md` for authentication details and security considerations
- `../deepsearch-web-app/README.md` for frontend deployment
- Main `README.md` for architecture and technical details

## Troubleshooting

**Backend won't start:**
- Check Java version: `java -version` (needs 17+)
- Check if port 8080 is free: `lsof -i :8080` (Mac/Linux)

**Frontend won't start:**
- Check Node version: `node -v` (needs 18+)
- Delete `node_modules` and run `npm install` again
- Check if port 3000 is free

**Search not working:**
- Make sure you created an API key
- Check browser console for errors
- Verify backend is running at http://localhost:8080
- Check that `GOOGLE_API_KEY` environment variable is set

**"Invalid or missing API key":**
- Make sure you copied the full API key (starts with `ds_live_`)
- Try creating a new API key
- Check browser console to see what key is being sent

## Development Tips

**Backend Hot Reload:**
```bash
# Use Gradle continuous build
./gradlew :presentation:run --continuous
```

**Frontend Hot Reload:**
- Already enabled by default with `npm run dev`
- Save any file and see changes instantly

**Check Backend Logs:**
- Logs appear in the terminal where you ran `./gradlew :presentation:run`
- Look for errors or API request logs

**Check Frontend Logs:**
- Open browser DevTools (F12)
- Check Console tab for errors
- Check Network tab to see API calls

## Architecture Overview

```
┌─────────────────┐
│   Next.js App   │  (React, MUI, TanStack Query)
│  localhost:3000 │
└────────┬────────┘
         │ HTTP/REST
         │ JWT + API Keys
         ▼
┌─────────────────┐
│   Ktor Server   │  (Kotlin, Exposed, Koin)
│  localhost:8080 │
└────────┬────────┘
         │
         ├─→ H2 Database (dev) / PostgreSQL (prod)
         ├─→ Playwright (web scraping)
         └─→ Google Gemini (AI processing)
```

## Key Concepts

- **JWT Authentication**: Used for user login and dashboard access
- **API Keys**: Used for programmatic access to search/cache APIs
- **Request Scoping**: Each request gets fresh service instances
- **Multi-Modal Extraction**: Handles text, images, tables, PDFs
- **Smart Caching**: Avoids re-crawling the same content

## Documentation

- **Full Auth Documentation**: `README-AUTH.md`
- **Frontend Documentation**: `../deepsearch-web-app/README.md`
- **Implementation Summary**: `../deepsearch-web-app/IMPLEMENTATION-SUMMARY.md`
- **Main Technical Docs**: `README.md`

## Support

Having issues? Check:
1. This quickstart guide
2. The troubleshooting section above
3. Backend terminal logs
4. Browser developer console
5. GitHub issues (if applicable)

---

**Happy Searching! 🔍✨**

