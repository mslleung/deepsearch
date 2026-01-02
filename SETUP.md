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

DeepSearch requires **PostgreSQL 17** with two extensions:
- **pgvector** - for vector similarity search
- **Apache AGE** - for graph database with Cypher query support

#### Option A: Using Docker (Recommended)

The easiest way to get started is using the custom Docker image that includes both extensions:

```bash
# Build and start the PostgreSQL container
docker-compose up -d postgres

# Verify it's running
docker exec -it deepsearch-postgres psql -U postgres -d deepsearch -c "SELECT * FROM pg_extension;"
```

The application will automatically enable both extensions on startup.

#### Option B: Manual Installation on macOS

##### Step 1: Install PostgreSQL 17

```bash
# Download PostgreSQL 17 installer from:
# https://www.enterprisedb.com/downloads/postgres-postgresql-downloads
# Select version 17.x for macOS

# After installation, add to PATH (add to ~/.zshrc or ~/.bashrc):
export PATH="/Library/PostgreSQL/17/bin:$PATH"

# Verify installation
pg_config --version  # Should show: PostgreSQL 17.x
```

During installation:
     - **Superuser Password:** Set to `password` (for local development)
     - **Port:** Use default port `5432`
     - **Locale:** Use default locale

##### Step 2: Install Build Dependencies

```bash
# Install Xcode command line tools (if not already installed)
xcode-select --install

# Install additional dependencies via Homebrew
brew install readline flex bison
```

##### Step 3: Install pgvector Extension

```bash
cd /tmp
git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git
cd pgvector

# Build and install
make PG_CONFIG=/Library/PostgreSQL/17/bin/pg_config
sudo make PG_CONFIG=/Library/PostgreSQL/17/bin/pg_config install
```

##### Step 4: Install Apache AGE Extension

```bash
cd /tmp
git clone --branch release/PG17/1.6.0 --depth 1 https://github.com/apache/age.git
cd age

# Build and install
make PG_CONFIG=/Library/PostgreSQL/17/bin/pg_config
sudo make PG_CONFIG=/Library/PostgreSQL/17/bin/pg_config install
```

##### Step 5: Restart PostgreSQL and Create Database

```bash
# IMPORTANT: Run pg_ctl from a neutral directory to avoid permission errors
cd /

# Restart PostgreSQL
sudo -u postgres /Library/PostgreSQL/17/bin/pg_ctl restart -D /Library/PostgreSQL/17/data -l /Library/PostgreSQL/17/data/log/postgresql.log

# Wait for server to start
sleep 3

# Create the deepsearch database
/Library/PostgreSQL/17/bin/psql -U postgres -c "CREATE DATABASE deepsearch;"
# Enter password: password
```

##### Step 6: Enable Extensions and Configure Database

```bash
/Library/PostgreSQL/17/bin/psql -U postgres -d deepsearch << 'EOF'
-- Enable extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS age;

-- Configure database to auto-load AGE for all connections (required for R2DBC)
ALTER DATABASE deepsearch SET session_preload_libraries = 'age';
ALTER DATABASE deepsearch SET search_path = ag_catalog, "$user", public;

-- Reconnect to apply settings
\c deepsearch

-- Create the knowledge graph (required for Cypher queries)
SELECT create_graph('knowledge_graph');

-- Verify extensions
SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector', 'age');
EOF
```

You should see output like:
```
 extname | extversion 
---------+------------
 vector  | 0.8.0
 age     | 1.6.0
(2 rows)
```

#### Option C: Manual Installation on Linux (Ubuntu/Debian)

##### Step 1: Install PostgreSQL 17

```bash
# Add PostgreSQL APT repository
sudo sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'
wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -
sudo apt-get update

# Install PostgreSQL 17
sudo apt-get install -y postgresql-17 postgresql-server-dev-17
```

##### Step 2: Install Build Dependencies

```bash
sudo apt-get install -y build-essential libreadline-dev zlib1g-dev flex bison git
```

##### Step 3: Install pgvector Extension

```bash
cd /tmp
git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git
cd pgvector
make
sudo make install
```

##### Step 4: Install Apache AGE Extension

```bash
cd /tmp
git clone --branch release/PG17/1.6.0 --depth 1 https://github.com/apache/age.git
cd age
make
sudo make install
```

##### Step 5: Restart PostgreSQL and Create Database

```bash
sudo systemctl restart postgresql

# Create database
sudo -u postgres psql -c "CREATE DATABASE deepsearch;"
```

##### Step 6: Verify Extensions

```bash
sudo -u postgres psql -d deepsearch << 'EOF'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';
SET search_path = ag_catalog, "$user", public;
SELECT * FROM pg_extension WHERE extname IN ('vector', 'age');
EOF
```

#### Option D: Manual Installation on Windows

##### Step 1: Install Build Tools

1. **Install Visual Studio Build Tools:**
   - Download [Visual Studio 2022 Build Tools](https://visualstudio.microsoft.com/downloads/#build-tools-for-visual-studio-2022)
   - During installation, select "Desktop development with C++"
   - Ensure "MSVC v143" and "Windows SDK" are checked

2. **Install Git for Windows:**
   - Download from [git-scm.com](https://git-scm.com/download/win)

##### Step 2: Install PostgreSQL 17

1. Download PostgreSQL 17 from [EDB Downloads](https://www.enterprisedb.com/downloads/postgres-postgresql-downloads)
2. Run the installer with these settings:
   - **Installation Directory:** `C:\Program Files\PostgreSQL\17`
   - **Data Directory:** `C:\Program Files\PostgreSQL\17\data`
     - **Password:** `password`
     - **Port:** `5432`
   - **Locale:** Default
3. Add PostgreSQL to PATH:
   - Open System Properties → Environment Variables
   - Add `C:\Program Files\PostgreSQL\17\bin` to the `Path` variable

##### Step 3: Install pgvector Extension

Open "x64 Native Tools Command Prompt for VS 2022" as Administrator:

```cmd
cd %TEMP%
git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git
cd pgvector

:: Set environment for PostgreSQL
set "PGROOT=C:\Program Files\PostgreSQL\17"

:: Build pgvector
nmake /F Makefile.win

:: Install (run as Administrator)
nmake /F Makefile.win install
```

##### Step 4: Install Apache AGE Extension

Continue in the same "x64 Native Tools Command Prompt for VS 2022":

```cmd
cd %TEMP%
git clone --branch release/PG17/1.6.0 https://github.com/apache/age.git
cd age

:: Set environment
set "PGROOT=C:\Program Files\PostgreSQL\17"

:: Build AGE
nmake /F Makefile.win

:: Install (run as Administrator)
nmake /F Makefile.win install
```

**Note:** If AGE doesn't have a `Makefile.win`, you may need to use WSL2 (Windows Subsystem for Linux) instead. See the Docker option or Linux instructions with WSL2.

##### Step 5: Alternative - Use WSL2 (Recommended for Windows)

If building natively on Windows is problematic, use WSL2:

1. **Install WSL2:**
   ```powershell
   wsl --install -d Ubuntu-22.04
   ```

2. **Inside WSL2, follow the Linux instructions** (Option C above)

3. **Update your `.env` file** to connect to WSL2's PostgreSQL:
   ```bash
   DB_HOST=localhost
   DB_PORT=5432
   ```

##### Step 6: Restart PostgreSQL and Create Database

Open Command Prompt as Administrator:

```cmd
:: Restart PostgreSQL service
net stop postgresql-x64-17
net start postgresql-x64-17

:: Create database
psql -U postgres -c "CREATE DATABASE deepsearch;"
```

##### Step 7: Verify Extensions

```cmd
psql -U postgres -d deepsearch -c "CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS age; SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector', 'age');"
```

#### Troubleshooting

1. **Perl not found during AGE build (macOS)**
   ```
   make: /Library/edb/languagepack/v5/Perl-5.40/bin/perl: No such file or directory
   ```
   This happens because PostgreSQL was built with EDB's language pack. Fix by creating a symlink:
   ```bash
   sudo mkdir -p /Library/edb/languagepack/v5/Perl-5.40/bin
   sudo ln -sf /usr/bin/perl /Library/edb/languagepack/v5/Perl-5.40/bin/perl
   # Then retry: make PG_CONFIG=/Library/PostgreSQL/17/bin/pg_config
   ```

2. **"getcwd: cannot access parent directories: Permission denied" (macOS)**
   This happens when starting PostgreSQL from a directory the postgres user can't access:
   ```bash
   # Always start PostgreSQL from a neutral directory
   cd /
   sudo -u postgres /Library/PostgreSQL/17/bin/pg_ctl start -D /Library/PostgreSQL/17/data -l /Library/PostgreSQL/17/data/log/postgresql.log
   ```

3. **Password authentication failed for user "postgres"**
   Reset the postgres password:
   ```bash
   # Stop PostgreSQL
   cd /
   sudo -u postgres /Library/PostgreSQL/17/bin/pg_ctl stop -D /Library/PostgreSQL/17/data
   
   # Temporarily allow passwordless access
   sudo cp /Library/PostgreSQL/17/data/pg_hba.conf /Library/PostgreSQL/17/data/pg_hba.conf.bak
   sudo sed -i '' 's/scram-sha-256/trust/g' /Library/PostgreSQL/17/data/pg_hba.conf
   sudo sed -i '' 's/md5/trust/g' /Library/PostgreSQL/17/data/pg_hba.conf
   
   # Start and change password
   sudo -u postgres /Library/PostgreSQL/17/bin/pg_ctl start -D /Library/PostgreSQL/17/data -l /Library/PostgreSQL/17/data/log/postgresql.log
   sleep 2
   /Library/PostgreSQL/17/bin/psql -U postgres -c "ALTER USER postgres WITH PASSWORD 'password';"
   
   # Restore original auth and restart
   sudo cp /Library/PostgreSQL/17/data/pg_hba.conf.bak /Library/PostgreSQL/17/data/pg_hba.conf
   sudo -u postgres /Library/PostgreSQL/17/bin/pg_ctl restart -D /Library/PostgreSQL/17/data -l /Library/PostgreSQL/17/data/log/postgresql.log
   ```

4. **Multiple PostgreSQL versions running (macOS)**
   If you have multiple PostgreSQL versions installed:
   ```bash
   # Check what's running
   ps aux | grep "/Library/PostgreSQL" | grep -v grep
   
   # Stop all PostgreSQL versions
   sudo pkill -9 -f "/Library/PostgreSQL/18/bin/postgres"
   sudo pkill -9 -f "/Library/PostgreSQL/16/bin/postgres"
   
   # Start only PG17
   cd /
   sudo -u postgres /Library/PostgreSQL/17/bin/pg_ctl start -D /Library/PostgreSQL/17/data -l /Library/PostgreSQL/17/data/log/postgresql.log
   ```

5. **"age.so: cannot open shared object file"**
   - Ensure AGE was built for the correct PostgreSQL version
   - Check that the .so file exists: `ls /Library/PostgreSQL/17/lib/age.so`

6. **"could not access file 'age': No such file or directory"**
   - The extension wasn't installed correctly. Rebuild and reinstall AGE.

7. **Permission denied errors during make install**
   - Use `sudo` for the install step

8. **PostgreSQL version mismatch**
   - AGE v1.6.0 requires PostgreSQL 17. Check your version with `pg_config --version`

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

