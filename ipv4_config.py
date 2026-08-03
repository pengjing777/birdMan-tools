import subprocess
import sys

def run_command(command):
    try:
        # 执行命令，如果失败则抛出异常
        subprocess.run(command, check=True, shell=True, capture_output=True, text=True)
        return True
    except subprocess.CalledProcessError as e:
        print(f"执行命令失败: {e.stderr}")
        return False

def set_to_dhcp(service_name="Wi-Fi"):
    print(f"正在将 {service_name} 切换为 DHCP...")
    # 切换为 DHCP
    run_command(f'networksetup -setdhcp "{service_name}"')
    print("切换完成。")

def set_to_manual(service_name="Wi-Fi", ip="192.168.40.232", subnet="255.255.255.0", router="192.168.40.1"):
    print(f"正在将 {service_name} 切换为手动 IP: {ip}...")
    # 依次设置 IP、子网掩码和路由器
    run_command(f'networksetup -setmanual "{service_name}" {ip} {subnet} {router}')
    print("切换完成。")
    
def modify_config(mode):
    if mode == "dhcp":
        set_to_dhcp(service)
    else:
        set_to_manual(service)


if __name__ == "__main__":
    # 使用示例
    # 请根据你的实际情况修改服务名称，例如 "Wi-Fi" 或 "Ethernet"
    service = "Wi-Fi" 
    
    # 切换逻辑 (可以通过参数控制)
    mode = "else" # 或者 "dhcp"
    
    modify_config(mode)