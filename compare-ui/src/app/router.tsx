import { createBrowserRouter } from 'react-router-dom'
import { AppShell } from '../components/AppShell'
import { DashboardPage } from '../pages/DashboardPage'
import { TasksPage } from '../pages/TasksPage'
import { TaskDetailPage } from '../pages/TaskDetailPage'
import { ConfigurationsPage } from '../pages/ConfigurationsPage'
import { ConfigurationEditorPage } from '../pages/ConfigurationEditorPage'
import { DatabasesPage } from '../pages/DatabasesPage'

export const router = createBrowserRouter([{ path: '/', element: <AppShell />, children: [
  { index: true, element: <DashboardPage /> },
  { path: 'tasks', element: <TasksPage /> },
  { path: 'tasks/:index/:date/:batch/:timeNode', element: <TaskDetailPage /> },
  { path: 'configurations', element: <ConfigurationsPage /> },
  { path: 'configurations/:index', element: <ConfigurationEditorPage /> },
  { path: 'databases', element: <DatabasesPage /> }
]}])
