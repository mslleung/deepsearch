# Development Setup

This guide will help you set up your development environment for DeepSearch.

## Prerequisites

### 1. Clone the Repository

```bash
git clone https://github.com/mslleung/deepsearch
cd deepsearch
```

### 2. Install Required Tools

#### Java Development Kit (JDK)
- Download and install [OpenJDK 24](https://jdk.java.net/24/)
- Add the `bin` folder to your system's `PATH` environment variable
- **Note:** Do NOT set `JAVA_HOME`; only update `PATH`

#### Node.js and npm
- **Windows users:** Install via [nvm-windows](https://github.com/coreybutler/nvm-windows)
- **macOS/Linux users:** Install via [nvm](https://github.com/nvm-sh/nvm) or your package manager

#### IDEs and Tools
- [IntelliJ IDEA Community Edition](https://www.jetbrains.com/idea/download/) - for Kotlin/Java development
- [Cursor](https://cursor.sh/) with the Kotlin extension (`fwcd.kotlin`)
- [DataGrip](https://www.jetbrains.com/datagrip/) or [DBeaver](https://dbeaver.io/) - for database management

### 3. Database Setup

#### PostgreSQL Installation

1. **Download and Install PostgreSQL:**
   - Download from [PostgreSQL Official Website](https://www.postgresql.org/)
   - During installation, configure the following:
     - **Superuser Password:** Set to `password` (for local development)
     - **Port:** Use default port `5432`
     - **Locale:** Use default locale

2. **Verify Installation:**
   - You can now connect to PostgreSQL using:
     - **Username:** `postgres`
     - **Password:** `password`
     - **Host:** `localhost`
     - **Port:** `5432`

#### pgvector Extension

pgvector is required for vector similarity search functionality.

1. **Install pgvector:**
   - Follow the installation guide at [pgvector GitHub](https://github.com/pgvector/pgvector)
   
2. **Windows Users:**
   - You may need to install C++ support in Visual Studio before installing pgvector
   - Download [Visual Studio](https://visualstudio.microsoft.com/) with C++ build tools if not already installed

3. **Verify Installation:**
   - Connect to your PostgreSQL database
   - Run: `CREATE EXTENSION IF NOT EXISTS vector;`

## Environment Configuration

### 1. Create Environment File

Create a `.env` file in the project root with the following content:

```bash
GOOGLE_CLOUD_PROJECT=deep-search-466804
GOOGLE_CLOUD_LOCATION=us-central1
GOOGLE_API_KEY=
GOOGLE_GENAI_USE_VERTEXAI=FALSE
```

### 2. Configure LLM Authentication

#### Development (Recommended - Free Tier)

1. **VPN Setup (for restricted regions):**
   - If Gemini is not available in your region (e.g., Hong Kong), use a VPN and connect to a supported region (e.g., Japan or Taiwan)

2. **Get API Key:**
   - Visit [Google AI Studio](https://aistudio.google.com/apikey)
   - Generate a free API key (use your company account, NOT the deepsearch project)
   - Paste the key into the `GOOGLE_API_KEY` field in your `.env` file

3. **Verify Settings:**
   - Ensure `GOOGLE_GENAI_USE_VERTEXAI=FALSE` in `.env`
   - Also update `.run/Template Gradle.run.xml` and `.run/Template Kotlin.run.xml` if they exist

#### Production (Costs Money - Optional)

⚠️ **Warning:** This option incurs costs. Only use if necessary.

1. Install [gcloud CLI](https://cloud.google.com/sdk/docs/install)
2. Authenticate:
   ```bash
   gcloud auth login
   gcloud auth application-default login
   ```
3. Set `GOOGLE_GENAI_USE_VERTEXAI=TRUE` in your `.env` file

### 3. Generate JWT Keys

Run the following commands to generate ES256 key pair for JWT authentication:

```bash
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out es256-private-key.pem
openssl ec -in es256-private-key.pem -pubout -out es256-public-key.pem
```

This will create `es256-private-key.pem` and `es256-public-key.pem` in your project root.

### 4. Configure Secrets

#### API Key HMAC Secret

Generate a secure random secret for HMAC-SHA256 API key hashing:

```bash
# Generate a random 32-byte (256-bit) secret encoded as base64
openssl rand -base64 32
```

Add the generated secret as an environment variable to your system or IDE run configuration:

```bash
API_KEY_HMAC_SECRET=<paste-your-generated-secret-here>
```

⚠️ **Important:** 
- Use a strong, randomly generated secret in production
- Never commit this secret to version control
- API keys are hashed one-way using HMAC-SHA256 and cannot be retrieved after creation

**For development, you can use this test secret:**
```bash
API_KEY_HMAC_SECRET=dev-hmac-secret-change-in-production-use-openssl-rand
```

#### Database Encryption Secret

Generate a secure random secret for AES-256-GCM database encryption:

```bash
# Generate a random 32-byte (256-bit) secret encoded as base64
openssl rand -base64 32
```

Add the generated secret as an environment variable to your system or IDE run configuration:

```bash
DATABASE_ENCRYPTION_SECRET=<paste-your-generated-secret-here>
```

⚠️ **Important:** 
- Use a strong, randomly generated secret in production
- Never commit this secret to version control
- This secret encrypts sensitive data like raw API keys stored in the database
- Changing this secret will make existing encrypted data unreadable

**For development, you can use this test secret:**
```bash
DATABASE_ENCRYPTION_SECRET=dev-encryption-secret-change-in-production-use-openssl-rand
```

## Running the Application

### Start the Server

Run the `Application.kt` file located in the `:presentation` module:

- In IntelliJ IDEA: Navigate to `presentation/src/main/kotlin/io/deepsearch/presentation/Application.kt` and click the green run button
- Or use Gradle: `./gradlew :presentation:run`

The server will start and be accessible at the configured port (check application configuration for details).

## Testing

### Running Tests

DeepSearch uses integration tests and unit tests as the primary testing methods.

#### Run All Tests
```bash
./gradlew test
```

#### Run Tests for a Specific Module
```bash
./gradlew :domain:test
./gradlew :application:test
./gradlew :infrastructure:test
./gradlew :presentation:test
```

### Alternative Testing Methods (Optional)

For quick manual testing during development:
- Set up main methods in domain agents (similar to Google ADK examples)
- Use the Google ADK Dev UI for simple interactive testing
- **Note:** These are useful for quick checks but don't replace proper test cases

## Troubleshooting

### Common Issues

1. **PostgreSQL Connection Issues:**
   - Verify PostgreSQL service is running
   - Check connection credentials (username: `postgres`, password: `password`)
   - Ensure port `5432` is not blocked by firewall
   - Test connection using: `psql -U postgres -h localhost`

2. **pgvector Extension Not Found:**
   - Ensure pgvector is properly installed for your PostgreSQL version
   - Windows users: Verify C++ build tools are installed
   - Try creating the extension manually: `CREATE EXTENSION IF NOT EXISTS vector;`

3. **Gemini API Not Working:**
   - Verify your VPN is connected to a supported region
   - Check that your API key is correctly set in `.env`
   - Ensure `GOOGLE_GENAI_USE_VERTEXAI=FALSE` for development

4. **Build Failures:**
   - Verify JDK is correctly added to PATH
   - Run `./gradlew clean build` to rebuild from scratch

## Additional Resources

- [Project README](README.md) - for project overview and architecture
- [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- [Domain-Driven Design](https://www.domainlanguage.com/ddd/) - architectural approach used in this project

---

**Ready to contribute?** Start by exploring the codebase and running the tests to ensure everything is set up correctly!

