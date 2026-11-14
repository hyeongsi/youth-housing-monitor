import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App.tsx'

const client = new QueryClient()
const legacyHashRoutes: Record<string, string> = { '#home': '/', '#announcements': '/announcements', '#preferences': '/preferences', '#programs': '/programs' }
const legacyRoute = legacyHashRoutes[window.location.hash]
if (legacyRoute) window.history.replaceState(null, '', legacyRoute)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter><QueryClientProvider client={client}><App /></QueryClientProvider></BrowserRouter>
  </StrictMode>,
)
