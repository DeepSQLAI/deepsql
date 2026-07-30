# ⚡ QUICK FIX for Build Error

## The Problem
Next.js can't read a file due to macOS security restrictions.

## The Solution (Copy & Paste This)

Open your terminal and run:

```bash
cd /Users/venkat/Documents/GitHub/dbaagent

# Stop the server (press Ctrl+C if npm run dev is running)

# Run the fix script
./fix-build-error.sh
```

**OR** run these commands manually:

```bash
cd /Users/venkat/Documents/GitHub/dbaagent

# Stop server (Ctrl+C)

# Remove the problematic directory
sudo rm -rf node_modules/next/dist/client/components/router-reducer

# Clear cache
rm -rf .next

# Reinstall Next.js
npm install next@14.2.0 --legacy-peer-deps --force

# Start server
npm run dev
```

## What This Does

1. Stops the current server
2. Removes the protected directory (requires sudo password)
3. Clears Next.js build cache
4. Reinstalls Next.js without the permission issues
5. Starts the server again

## After Running

The page should load at http://localhost:3000 without errors.

---

**Note:** You'll be prompted for your sudo password to remove the protected directory. This is safe and necessary to fix the macOS permission issue.

