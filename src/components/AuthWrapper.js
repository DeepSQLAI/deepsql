'use client';

import { useEffect } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { AUTH_CHANGE_EVENT } from '@/lib/api/client';

export default function AuthWrapper({ children }) {
    const router = useRouter();
    const pathname = usePathname();

    // Check auth state
    const checkAuth = async () => {
        let hasSession = false;
        try {
            const response = await fetch('/api/auth/me', { credentials: 'include' });
            hasSession = response.ok;
        } catch {
            hasSession = false;
        }
        console.log('🔍 AuthWrapper check:', { pathname, hasSession });

        // Redirect unauthenticated users to login (but not if already on auth pages)
        if (!hasSession && !pathname.startsWith('/auth/')) {
            console.log('🔒 No active session, redirecting to login from:', pathname);
            router.push('/auth/login/');
            return;
        }

        // Redirect authenticated users away from auth pages to home
        if (hasSession && pathname.startsWith('/auth/')) {
            console.log('✅ Session exists, redirecting to home from:', pathname);
            router.push('/');
            return;
        }

        // User is on the correct page for their auth state
        console.log('✓ Auth check passed, showing page:', pathname);
    };

    useEffect(() => {
        void checkAuth();

        // Listen for auth state changes (e.g., 401 logout)
        const handleAuthChange = (event) => {
            console.log('🔄 Auth state changed:', event.detail);
            if (event.detail.action === 'logout') {
                // Token already cleared by interceptor, just redirect
                router.push('/auth/login/');
            }
        };

        window.addEventListener(AUTH_CHANGE_EVENT, handleAuthChange);

        return () => {
            window.removeEventListener(AUTH_CHANGE_EVENT, handleAuthChange);
        };
    }, [pathname, router]);

    // Show nothing while checking auth to prevent flash
    return <>{children}</>;
}
