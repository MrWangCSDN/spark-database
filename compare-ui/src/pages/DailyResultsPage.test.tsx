import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderPage } from '../test/render'
import { server } from '../test/server'
import { DailyResultsPage } from './DailyResultsPage'

const emptyResults = {
  results: { items: [], total: 0, page: 0, size: 20 },
  connections: []
}

describe('DailyResultsPage export', () => {
  const createObjectURL = vi.fn(() => 'blob:daily-result')
  const revokeObjectURL = vi.fn()
  let clickedDownload = ''
  let clickedHref = ''

  beforeEach(() => {
    createObjectURL.mockClear()
    revokeObjectURL.mockClear()
    clickedDownload = ''
    clickedHref = ''
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clickedDownload = this.download
      clickedHref = this.href
    })
    server.use(http.get('/api/results', () => HttpResponse.json(emptyResults)))
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('downloads the currently queried date using the response filename', async () => {
    let requestedDate = ''
    server.use(http.get('/api/results/export', ({ request }) => {
      requestedDate = new URL(request.url).searchParams.get('date') ?? ''
      return new HttpResponse(new Uint8Array([0x50, 0x4b, 0x03, 0x04]), {
        headers: {
          'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          'Content-Disposition': "attachment; filename=\"daily-results.xlsx\"; filename*=UTF-8''%E6%AF%8F%E6%97%A5%E6%A0%B8%E5%AF%B9%E7%BB%93%E6%9E%9C-20260920.xlsx"
        }
      })
    }))
    const user = userEvent.setup()
    renderPage(<DailyResultsPage />, '/results?date=20260920')

    await user.clear(screen.getByLabelText('核对业务日期'))
    await user.type(screen.getByLabelText('核对业务日期'), '20261001')
    await user.click(screen.getByRole('button', { name: '导出报表' }))

    await waitFor(() => expect(clickedDownload).toBe('每日核对结果-20260920.xlsx'))
    expect(requestedDate).toBe('20260920')
    expect(clickedHref).toContain('blob:daily-result')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:daily-result')
  })

  it('shows reviser beside object and sends three fuzzy filters', async () => {
    let requested = new URLSearchParams()
    server.use(http.get('/api/results', ({ request }) => {
      requested = new URL(request.url).searchParams
      return HttpResponse.json({
        results: { page: 0, size: 20, total: 1, items: [{
          bcompIndex: 1, resultDate: '20260920', configurationName: 'acct', configurationModule: 'dept',
          reviserName: '王山河', reviserUsername: 'c-wangsh8', originDatabaseId: 1, originDatabaseName: 'sit',
          destinationDatabaseId: 2, destinationDatabaseName: 'uat', originCount: 1, destinationCount: 1,
          onlyOriginCount: 0, onlyDestinationCount: 0, differentCount: 0
        }] },
        connections: [{ originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat', objectCount: 1 }]
      })
    }))
    const user = userEvent.setup()
    renderPage(<DailyResultsPage />, '/results?date=20260920')
    expect(await screen.findByText('王山河(c-wangsh8)')).toBeInTheDocument()
    const headings = screen.getAllByRole('columnheader').map(node => node.textContent)
    expect(headings.indexOf('负责人')).toBe(headings.indexOf('表 / 核对对象') + 1)
    await user.type(screen.getByLabelText('领域'), '存款')
    await user.type(screen.getByLabelText('表 / 核对对象'), 'acc')
    await user.type(screen.getByLabelText('负责人'), 'wang')
    await user.click(screen.getByRole('button', { name: '查询结果' }))
    await waitFor(() => expect(requested.get('reviser')).toBe('wang'))
    expect(requested.get('domain')).toBe('存款')
    expect(requested.get('configurationName')).toBe('acc')
  })

  it('shows a placeholder for historical results without reviser data', async () => {
    server.use(http.get('/api/results', () => HttpResponse.json({
      results: { page: 0, size: 20, total: 1, items: [{
        bcompIndex: 1, configurationName: '旧结果', configurationModule: 'dept', reviserName: null, reviserUsername: null,
        originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat',
        originCount: 0, destinationCount: 0, onlyOriginCount: 0, onlyDestinationCount: 0, differentCount: 0
      }] },
      connections: [{ originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat', objectCount: 1 }]
    })))
    renderPage(<DailyResultsPage />, '/results?date=20260920')
    const row = await screen.findByText('旧结果')
    expect(row.closest('tr')).toHaveTextContent('—')
  })

  it('shows the server error and never saves an error response as xlsx', async () => {
    server.use(http.get('/api/results/export', () => HttpResponse.json({
      code: 'RESULT_NOT_FOUND',
      message: '该日期没有可导出的核对结果'
    }, { status: 404 })))
    const user = userEvent.setup()
    renderPage(<DailyResultsPage />, '/results?date=20260920')

    await user.click(screen.getByRole('button', { name: '导出报表' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('该日期没有可导出的核对结果')
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(clickedDownload).toBe('')
  })

  it('showsRerunMessageWithoutDownloadingJsonWhenFieldCountsAreUnavailable', async () => {
    server.use(http.get('/api/results/export', () => HttpResponse.json({
      code: 'FIELD_COUNTS_UNAVAILABLE', message: '部分表缺少完整字段统计，请重新核对后导出。', traceId: 'export-test'
    }, { status: 409 })))
    renderPage(<DailyResultsPage />, '/results?date=20260920')
    await userEvent.setup().click(screen.getByRole('button', { name: '导出报表' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('请重新核对后导出')
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(clickedDownload).toBe('')
    expect(screen.getByRole('button', { name: '导出报表' })).toBeEnabled()
  })

  it('disables repeated export clicks while the download is pending', async () => {
    let release!: () => void
    const pending = new Promise<void>(resolve => { release = resolve })
    let requestCount = 0
    server.use(http.get('/api/results/export', async () => {
      requestCount += 1
      await pending
      return new HttpResponse(new Uint8Array([0x50, 0x4b]), {
        headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }
      })
    }))
    const user = userEvent.setup()
    renderPage(<DailyResultsPage />, '/results?date=20260920')

    const button = screen.getByRole('button', { name: '导出报表' })
    await user.click(button)

    expect(screen.getByRole('button', { name: '正在导出…' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '正在导出…' }))
    expect(requestCount).toBe(1)
    release()
    await waitFor(() => expect(screen.getByRole('button', { name: '导出报表' })).toBeEnabled())
  })
})

it('shows the saved source, display name and scope without hiding the fixed code', async () => {
  server.use(http.get('/api/results', () => HttpResponse.json({
    results: { page: 0, size: 20, total: 1, items: [{
      bcompIndex: 2, configurationName: 'a_1', sourceTableName: 'a', configurationDisplayName: '配置乙', configurationScope: 'b=2',
      originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat',
      originCount: 2, destinationCount: 2, onlyOriginCount: 0, onlyDestinationCount: 0, differentCount: 1
    }] }, connections: [{ originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat', objectCount: 1 }]
  })))
  renderPage(<DailyResultsPage />, '/results?date=20261126')
  expect(await screen.findByText('配置乙')).toBeVisible()
  expect(screen.getByText('a_1')).toBeVisible()
  expect(screen.getByText('b=2')).toBeVisible()
})
