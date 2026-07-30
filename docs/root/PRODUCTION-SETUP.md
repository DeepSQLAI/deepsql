# DBA Agent - Production Setup Guide

## Domain Structure

- **Frontend**: `https://dba-agent-app.stayflexi.com` (Next.js application)
- **Backend API**: `https://dba-agent-api.stayflexi.com` (Spring Boot REST API)

---

## Changes Made

### 1. Frontend API Configuration
Updated `src/lib/api/client.js` to use the new API domain:
- Production API: `https://dba-agent-api.stayflexi.com`
- Development API: `http://localhost:8080`

### 2. Backend CORS Configuration
Updated `backend/src/main/java/com/dbaagent/config/SecurityConfig.java` to allow:
- `https://dba-agent-api.stayflexi.com`
- `https://dba-agent-app.stayflexi.com`

### 3. Nginx Configuration
Created `nginx-production.conf` with separate server blocks for:
- API server on `dba-agent-api.stayflexi.com`
- Frontend app on `dba-agent-app.stayflexi.com`

---

## Production Deployment Steps

### Step 1: DNS Configuration

Add A records for both domains pointing to your server IP:
```
dba-agent-api.stayflexi.com  -> YOUR_SERVER_IP
dba-agent-app.stayflexi.com  -> YOUR_SERVER_IP
```

### Step 2: Deploy Backend

```bash
# On your local machine
cd /Users/geekypunk/sasank/stayflexi/dba-agent/backend
mvn clean package -DskipTests

# Copy to production server
scp target/dbaagent-0.0.1-SNAPSHOT.jar your-server:/opt/dba-agent/dbaagent.jar

# On production server - create systemd service
sudo nano /etc/systemd/system/dba-agent.service
```

Systemd service content:
```ini
[Unit]
Description=DBA Agent Backend API
After=network.target

[Service]
Type=simple
User=www-data
WorkingDirectory=/opt/dba-agent
ExecStart=/usr/bin/java -jar /opt/dba-agent/dbaagent.jar
Restart=always
RestartSec=10
StandardOutput=append:/var/log/dba-agent/output.log
StandardError=append:/var/log/dba-agent/error.log

Environment="SPRING_PROFILES_ACTIVE=prod"
Environment="SERVER_PORT=8080"

[Install]
WantedBy=multi-user.target
```

Start the backend:
```bash
sudo mkdir -p /opt/dba-agent /var/log/dba-agent
sudo chown -R www-data:www-data /opt/dba-agent /var/log/dba-agent
sudo systemctl daemon-reload
sudo systemctl enable dba-agent
sudo systemctl start dba-agent
sudo systemctl status dba-agent

# Verify it's running
curl http://localhost:8080/api/auth/login
```

### Step 3: Deploy Frontend

```bash
# On your local machine - build for production
cd /Users/geekypunk/sasank/stayflexi/dba-agent
npm run build

# Export static files
npx next export

# Copy to production server
scp -r out/* your-server:/var/www/dba-agent-app/

# On production server
sudo chown -R www-data:www-data /var/www/dba-agent-app
```

### Step 4: Configure Nginx

```bash
# Copy the nginx configuration
scp nginx-production.conf your-server:/tmp/

# On production server
sudo cp /tmp/nginx-production.conf /etc/nginx/sites-available/dba-agent
sudo ln -s /etc/nginx/sites-available/dba-agent /etc/nginx/sites-enabled/

# Remove old configuration if exists
sudo rm /etc/nginx/sites-enabled/default

# Test nginx configuration
sudo nginx -t

# Reload nginx
sudo systemctl reload nginx
```

### Step 5: Setup SSL with Let's Encrypt

```bash
# Install certbot
sudo apt update
sudo apt install certbot python3-certbot-nginx

# Get SSL certificates for both domains
sudo certbot --nginx -d dba-agent-api.stayflexi.com -d dba-agent-app.stayflexi.com

# Certbot will automatically update your nginx config
# Verify auto-renewal works
sudo certbot renew --dry-run
```

### Step 6: Verify Everything Works

```bash
# Test backend directly
curl http://localhost:8080/api/auth/login

# Test backend through nginx
curl https://dba-agent-api.stayflexi.com/api/auth/login

# Test frontend
curl https://dba-agent-app.stayflexi.com
```

---

## Environment Variables

### Production Environment (.env.production)

Create `.env.production` in the frontend root:
```bash
NEXT_PUBLIC_API_URL=https://dba-agent-api.stayflexi.com
NODE_ENV=production
```

Rebuild frontend after creating this file:
```bash
npm run build
npx next export
```

---

## Troubleshooting

### Backend not starting
```bash
# Check logs
sudo journalctl -u dba-agent -n 100 --no-pager

# Check if port 8080 is in use
sudo lsof -i :8080

# Test JAR manually
cd /opt/dba-agent
java -jar dbaagent.jar
```

### CORS errors
- Verify `SecurityConfig.java` has correct allowed origins
- Check browser console for exact CORS error
- Verify nginx is passing through headers correctly

### SSL certificate issues
```bash
# Check certificate status
sudo certbot certificates

# Force renew
sudo certbot renew --force-renewal
```

### 404 errors on API
```bash
# Verify backend is running
curl http://localhost:8080/api/auth/login

# Check nginx is proxying correctly
sudo tail -f /var/log/nginx/error.log
sudo tail -f /var/log/nginx/access.log
```

---

## Quick Reference Commands

```bash
# Restart backend
sudo systemctl restart dba-agent

# View backend logs
sudo journalctl -u dba-agent -f

# Reload nginx (no downtime)
sudo systemctl reload nginx

# Restart nginx
sudo systemctl restart nginx

# Check all services
sudo systemctl status dba-agent nginx

# Monitor ports
sudo lsof -i :8080  # Backend
sudo lsof -i :80    # Nginx HTTP
sudo lsof -i :443   # Nginx HTTPS
```

---

## Security Checklist

- [x] API and App on separate domains
- [ ] SSL/TLS certificates installed
- [ ] Firewall configured (only 80, 443, 22 open)
- [ ] Backend only accessible from localhost
- [ ] CORS properly configured
- [ ] Strong JWT secret in production
- [ ] Database credentials encrypted
- [ ] Regular security updates

---

## Next Steps After Deployment

1. Test login from `https://dba-agent-app.stayflexi.com`
2. Create your first database connection
3. Monitor logs for any errors
4. Set up automated backups
5. Configure monitoring/alerting
