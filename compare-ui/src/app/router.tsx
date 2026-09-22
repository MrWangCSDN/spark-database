import { createBrowserRouter } from 'react-router-dom'
import { AppShell } from '../components/AppShell'
import { DailyResultsPage } from '../pages/DailyResultsPage'
import { DailyResultDetailPage } from '../pages/DailyResultDetailPage'
import { DashboardPage } from '../pages/DashboardPage'
import { BatchesPage } from '../pages/BatchesPage'
import { BatchCreatePage } from '../pages/BatchCreatePage'
import { BatchDetailPage } from '../pages/BatchDetailPage'
import { TasksPage } from '../pages/TasksPage'
import { TaskDetailPage } from '../pages/TaskDetailPage'
import { ConfigurationsPage } from '../pages/ConfigurationsPage'
import { ConfigurationEditorPage } from '../pages/ConfigurationEditorPage'
import { ConfigurationImportPage } from '../pages/ConfigurationImportPage'
import { DatabasesPage } from '../pages/DatabasesPage'

export const router = createBrowserRouter([{ path: '/', element: <AppShell />, children: [
  { index: true, element: <DashboardPage /> },
  { path: 'results', element: <DailyResultsPage /> },
  { path: 'results/:index/:date', element: <DailyResultDetailPage /> },
  { path: 'batches', element: <BatchesPage /> },
  { path: 'batches/new', element: <BatchCreatePage /> },
  { path: 'batches/:batchId', element: <BatchDetailPage /> },
  { path: 'tasks', element: <TasksPage /> },
  { path: 'tasks/:index/:date/:batch/:timeNode', element: <TaskDetailPage /> },
  { path: 'configurations', element: <ConfigurationsPage /> },
  { path: 'configurations/new', element: <ConfigurationImportPage /> },
  { path: 'configurations/:index', element: <ConfigurationEditorPage /> },
  { path: 'databases', element: <DatabasesPage /> }
]}])
