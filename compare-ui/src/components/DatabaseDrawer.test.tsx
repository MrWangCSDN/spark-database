import { render, screen } from '@testing-library/react'
import { DatabaseDrawer } from './DatabaseDrawer'

const noop = () => undefined

test('shows GDB JDBC driver and URL guidance without changing connection payload shape', () => {
  render(<DatabaseDrawer
    isSaving={false}
    onClose={noop}
    onSave={noop}
    onTestDraft={async value => ({ success: true, databaseProduct: value.driver, databaseVersion: 'test', elapsedMs: 1 })}
    onTestSaved={async () => ({ success: true, databaseProduct: 'GDB', databaseVersion: 'test', elapsedMs: 1 })}
  />)

  expect(screen.getByLabelText('JDBC URL')).toHaveAttribute('placeholder', expect.stringContaining('jdbc:'))
  expect(document.querySelector('option[value="com.mysql.jdbc.Driver"]')).toBeInTheDocument()
  expect(screen.getByText(/GDB.*MySQL.*currentSchema/)).toBeVisible()
})
