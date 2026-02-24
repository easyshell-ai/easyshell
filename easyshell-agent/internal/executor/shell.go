package executor

import (
	"bufio"
	"bytes"
	"context"
	"io"
	"log/slog"
	"os/exec"
	"sync"
)

type LogCallback func(line string)

type Executor struct{}

func New() *Executor {
	return &Executor{}
}

func (e *Executor) Execute(ctx context.Context, script string) (output string, exitCode int, err error) {
	slog.Info("executing script", "length", len(script))

	cmd := exec.CommandContext(ctx, "/bin/sh", "-c", script)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	err = cmd.Run()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return stdout.String() + stderr.String(), exitErr.ExitCode(), nil
		}
		return "", -1, err
	}

	return stdout.String() + stderr.String(), 0, nil
}

func (e *Executor) ExecuteWithCallback(ctx context.Context, script string, onLog LogCallback) (exitCode int, err error) {
	slog.Info("executing script with callback", "length", len(script))

	cmd := exec.CommandContext(ctx, "/bin/sh", "-c", script)

	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		return -1, err
	}
	stderrPipe, err := cmd.StderrPipe()
	if err != nil {
		return -1, err
	}

	if err := cmd.Start(); err != nil {
		return -1, err
	}

	var wg sync.WaitGroup
	wg.Add(2)

	streamLines := func(r io.Reader) {
		defer wg.Done()
		scanner := bufio.NewScanner(r)
		scanner.Buffer(make([]byte, 64*1024), 1024*1024)
		for scanner.Scan() {
			if onLog != nil {
				onLog(scanner.Text())
			}
		}
	}

	go streamLines(stdoutPipe)
	go streamLines(stderrPipe)

	wg.Wait()

	err = cmd.Wait()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return exitErr.ExitCode(), nil
		}
		return -1, err
	}

	return 0, nil
}
