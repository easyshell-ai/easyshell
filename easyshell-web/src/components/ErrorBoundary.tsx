import React from 'react';
import { Result, Button } from 'antd';
import { withTranslation, type WithTranslation } from 'react-i18next';

interface ErrorBoundaryProps extends WithTranslation {
  children: React.ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

/**
 * Error boundary wrapper for catching render errors.
 * Displays a friendly error message with a retry button.
 */
class ErrorBoundaryInner extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    console.error('ErrorBoundary caught an error:', error, errorInfo);
  }

  handleReset = (): void => {
    this.setState({ hasError: false, error: null });
  };

  render(): React.ReactNode {
    const { t } = this.props;
    if (this.state.hasError) {
      return (
        <Result
          status="error"
          title={t('error.pageError')}
          subTitle={this.state.error?.message || t('error.unknownError')}
          extra={[
            <Button key="retry" type="primary" onClick={this.handleReset}>
              {t('common.retry')}
            </Button>,
            <Button key="home" onClick={() => (window.location.href = '/')}>
              {t('error.backHome')}
            </Button>,
          ]}
        />
      );
    }

    return this.props.children;
  }
}

const ErrorBoundary = withTranslation()(ErrorBoundaryInner);
export default ErrorBoundary;
