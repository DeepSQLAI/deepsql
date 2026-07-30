# Quick Start Guide

## ✅ Frontend Status
**Frontend is currently running on:** http://localhost:3000

You can access it in your browser now!

## 🚀 Starting the Backend

The backend requires Java 17+ and Maven. Here's how to set it up:

### Step 1: Install Java 17

**macOS (using Homebrew):**
```bash
brew install openjdk@17
```

**Or download from:**
- https://adoptium.net/ (recommended)
- Select Java 17 LTS for macOS

**Verify installation:**
```bash
java -version
# Should show: openjdk version "17.x.x"
```

### Step 2: Install Maven

**macOS (using Homebrew):**
```bash
brew install maven
```

**Verify installation:**
```bash
mvn -version
```

### Step 3: Start the Backend

**Option A: Using the helper script**
```bash
./start-backend.sh
```

**Option B: Manual start**
```bash
cd backend
mvn spring-boot:run
```

The backend will start on: **http://localhost:8080**

### Step 4: Verify Both Services

1. **Backend**: Open http://localhost:8080/api/connections
   - Should return: `[]` (empty array)

2. **Frontend**: Already running at http://localhost:3000
   - You should see the DBA Agent interface

## 🎯 Next Steps

1. Open http://localhost:3000 in your browser
2. Click the Settings icon (⚙️) in the left panel
3. Connect to your MySQL or PostgreSQL database
4. View schema visualizations and DBA stats!

## 📝 Notes

- The frontend is already running in the background
- You need to start the backend separately in a new terminal
- Both services need to be running for full functionality
- Backend stores encrypted credentials in `backend/data/vault.mv.db`

## 🐛 Troubleshooting

**Backend won't start:**
- Ensure Java 17+ is installed: `java -version`
- Ensure Maven is installed: `mvn -version`
- Check if port 8080 is available: `lsof -i :8080`

**Frontend can't connect to backend:**
- Ensure backend is running on port 8080
- Check browser console for errors
- Verify `.env.local` has: `NEXT_PUBLIC_API_URL=http://localhost:8080`


