package collector

import (
	"net"
	"os"
	"runtime"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/shirou/gopsutil/v4/host"
	"github.com/shirou/gopsutil/v4/mem"
)

type SystemInfo struct {
	Hostname  string  `json:"hostname"`
	IP        string  `json:"ip"`
	OS        string  `json:"os"`
	Arch      string  `json:"arch"`
	Kernel    string  `json:"kernel"`
	CPUModel  string  `json:"cpuModel"`
	CPUCores  int     `json:"cpuCores"`
	MemTotal  uint64  `json:"memTotal"`
	CPUUsage  float64 `json:"cpuUsage"`
	MemUsage  float64 `json:"memUsage"`
	DiskUsage float64 `json:"diskUsage"`
}

func Collect() (*SystemInfo, error) {
	hostname, _ := os.Hostname()
	cpuCounts, _ := cpu.Counts(true)

	memStat, err := mem.VirtualMemory()
	if err != nil {
		return nil, err
	}

	diskStat, err := disk.Usage("/")
	if err != nil {
		return nil, err
	}

	cpuPercent, _ := cpu.Percent(0, false)
	var cpuUsage float64
	if len(cpuPercent) > 0 {
		cpuUsage = cpuPercent[0]
	}

	var cpuModel string
	cpuInfos, _ := cpu.Info()
	if len(cpuInfos) > 0 {
		cpuModel = cpuInfos[0].ModelName
	}

	kernel := getKernel()
	ip := getLocalIP()

	return &SystemInfo{
		Hostname:  hostname,
		IP:        ip,
		OS:        runtime.GOOS,
		Arch:      runtime.GOARCH,
		Kernel:    kernel,
		CPUModel:  cpuModel,
		CPUCores:  cpuCounts,
		MemTotal:  memStat.Total,
		CPUUsage:  cpuUsage,
		MemUsage:  memStat.UsedPercent,
		DiskUsage: diskStat.UsedPercent,
	}, nil
}

func getKernel() string {
	info, err := host.Info()
	if err != nil {
		return ""
	}
	return info.KernelVersion
}

func getLocalIP() string {
	// Use pure Go net package — works on all Linux distros without external commands
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "127.0.0.1"
	}
	for _, addr := range addrs {
		if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() && ipNet.IP.To4() != nil {
			return ipNet.IP.String()
		}
	}
	return "127.0.0.1"
}

func CollectMetrics() (cpuUsage, memUsage, diskUsage float64) {
	cpuPercent, _ := cpu.Percent(0, false)
	if len(cpuPercent) > 0 {
		cpuUsage = cpuPercent[0]
	}

	memStat, err := mem.VirtualMemory()
	if err == nil {
		memUsage = memStat.UsedPercent
	}

	diskStat, err := disk.Usage("/")
	if err == nil {
		diskUsage = diskStat.UsedPercent
	}

	return cpuUsage, memUsage, diskUsage
}
