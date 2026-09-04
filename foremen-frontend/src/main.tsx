import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { Toaster } from 'sonner'
import { queryClient } from '@/lib/query-client'
import { router } from '@/app/router'
import { useAuthStore } from '@/stores/auth-store'
import '@/lib/i18n'
import './index.css'

// Kick off Session_Hydration before the first render so the Auth_Guard observes
// the correct `hydrationStatus` transition (`pending` -> `done`) and does not
// prematurely redirect an as-yet-undetermined session to `/login` (Req 4.1, 4.5,
// 9.4). `hydrate()` is fire-and-forget: the store starts in `hydrationStatus:
// 'pending'`, ProtectedLayout renders its loading indicator until this resolves,
// and the promise itself never rejects (all failures resolve to a cleared
// session inside the store).
void useAuthStore.getState().hydrate()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Toaster position="top-right" richColors />
    </QueryClientProvider>
  </React.StrictMode>,
)
