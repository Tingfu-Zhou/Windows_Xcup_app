#!/usr/bin/env python3
"""
BLE服务进程 - 使用Bleak库实现完整的BLE通信
与Java主程序通过标准输入输出进行JSON通信

修复: 解决应用关闭时stdout管道关闭导致的OSError错误
"""

import asyncio
import sys
import json
import struct
import traceback
from datetime import datetime
from typing import Optional
from bleak import BleakClient, BleakScanner, BleakError

class BLEService:
    def __init__(self):
        # BLE UUIDs (与Android版本保持一致)
        self.SERVICE_UUID = "e43c4cbf-9e30-44cc-b8ea-83561908a4e5"
        self.RX_CHAR_UUID = "71cd6e15-8ed6-4727-b306-42a0e20fe7b6"  # App->Dev
        self.TX_CHAR_UUID = "2cbb355f-d59a-4be6-aaab-fcfe27abcec4"  # Dev->App

        self.TARGET_NAME_PREFIX = "XCUP-A1B2"

        self.client: Optional[BleakClient] = None
        self.is_connected = False
        self.device_address = None
        self.device_name = None

        # [FIX] 添加管道状态标志
        self._pipe_closed = False
        self._shutting_down = False

    def log(self, message: str, level: str = "INFO"):
        """发送日志消息到Java端"""
        self.send_message({
            "type": "log",
            "level": level,
            "message": message,
            "timestamp": datetime.now().isoformat()
        })

    def send_message(self, message: dict):
        """发送JSON消息到Java端"""
        # [FIX] 如果管道已关闭，直接返回不尝试写入
        if self._pipe_closed:
            return

        try:
            print(json.dumps(message), flush=True)
        except (OSError, IOError, BrokenPipeError) as e:
            # [FIX] 管道相关错误，标记管道已关闭，不再尝试写入
            self._pipe_closed = True
            # 写入stderr作为最后的调试手段（不会触发弹窗）
            try:
                sys.stderr.write(f"[BLE] Pipe closed: {e}\n")
                sys.stderr.flush()
            except:
                pass
        except Exception as e:
            # [FIX] 其他异常也不要再尝试写入stdout，避免递归
            try:
                sys.stderr.write(f"[BLE] Send error: {e}\n")
                sys.stderr.flush()
            except:
                pass

    async def scan_devices(self, timeout: float = 10.0):
        """扫描BLE设备"""
        self.log(f"开始扫描BLE设备, 请耐心等待 (超时: {timeout}秒)...")

        try:
            devices = await BleakScanner.discover(timeout=timeout)

            found_devices = []
            target_device = None

            for device in devices:
                device_info = {
                    "address": device.address,
                    "name": device.name or "Unknown",
                    "rssi": device.rssi if hasattr(device, 'rssi') else -100
                }

                self.log(f"发现设备: {device_info['name']} [{device_info['address']}] RSSI: {device_info['rssi']}")
                found_devices.append(device_info)

                # 检查是否为目标设备
                if device.name and device.name.startswith(self.TARGET_NAME_PREFIX):
                    target_device = device
                    self.device_address = device.address
                    self.device_name = device.name
                    self.log(f"找到目标设备: {device.name}", "SUCCESS")

            self.send_message({
                "type": "scan_result",
                "devices": found_devices,
                "target_found": target_device is not None,
                "target_address": self.device_address if target_device else None,
                "target_name": self.device_name if target_device else None
            })

            return target_device is not None

        except Exception as e:
            self.log(f"扫描失败: {str(e)}", "ERROR")
            self.send_message({
                "type": "scan_result",
                "error": str(e),
                "devices": [],
                "target_found": False
            })
            return False

    async def connect(self, address: Optional[str] = None):
        """连接到BLE设备"""
        try:
            if not address and not self.device_address:
                # 先扫描
                if not await self.scan_devices():
                    self.send_message({
                        "type": "connect_result",
                        "success": False,
                        "error": "未找到目标设备"
                    })
                    return False

            address = address or self.device_address

            self.log(f"正在连接到设备: {address}...")

            # 创建客户端并连接
            self.client = BleakClient(address)
            await self.client.connect(timeout=20.0)

            if self.client.is_connected:
                self.is_connected = True
                self.log("连接成功!", "SUCCESS")

                # 获取服务信息
                services = self.client.services

                # 检查目标服务是否存在
                service_found = False
                rx_found = False
                tx_found = False

                for service in services:
                    self.log(f"服务: {service.uuid}")

                    if service.uuid.lower() == self.SERVICE_UUID.lower():
                        service_found = True

                        for char in service.characteristics:
                            self.log(f"  特征: {char.uuid} - 属性: {char.properties}")

                            if char.uuid.lower() == self.RX_CHAR_UUID.lower():
                                rx_found = True
                                self.log(f"  找到RX特征 (App->Dev)")

                            elif char.uuid.lower() == self.TX_CHAR_UUID.lower():
                                tx_found = True
                                self.log(f"  找到TX特征 (Dev->App)")

                                # 订阅通知
                                if "notify" in char.properties:
                                    await self.client.start_notify(
                                        self.TX_CHAR_UUID,
                                        self.notification_handler
                                    )
                                    self.log("已订阅TX特征通知", "SUCCESS")

                self.send_message({
                    "type": "connect_result",
                    "success": True,
                    "address": address,
                    "name": self.device_name,
                    "service_found": service_found,
                    "rx_found": rx_found,
                    "tx_found": tx_found
                })

                return True
            else:
                self.send_message({
                    "type": "connect_result",
                    "success": False,
                    "error": "连接失败"
                })
                return False

        except asyncio.TimeoutError:
            self.log("连接超时", "ERROR")
            self.send_message({
                "type": "connect_result",
                "success": False,
                "error": "连接超时"
            })
            return False

        except Exception as e:
            self.log(f"连接失败: {str(e)}", "ERROR")
            self.send_message({
                "type": "connect_result",
                "success": False,
                "error": str(e)
            })
            return False

    async def disconnect(self):
        """断开连接"""
        try:
            if self.client:
                if self.client.is_connected:
                    # 停止通知
                    try:
                        await self.client.stop_notify(self.TX_CHAR_UUID)
                    except:
                        pass

                    await self.client.disconnect()
                    # [FIX] 只在管道未关闭时发送日志
                    if not self._pipe_closed:
                        self.log("已断开连接", "INFO")

                self.client = None

            self.is_connected = False

            # [FIX] 只在管道未关闭时发送结果
            if not self._pipe_closed:
                self.send_message({
                    "type": "disconnect_result",
                    "success": True
                })

        except Exception as e:
            # [FIX] 只在管道未关闭时发送错误
            if not self._pipe_closed:
                self.log(f"断开连接失败: {str(e)}", "ERROR")
                self.send_message({
                    "type": "disconnect_result",
                    "success": False,
                    "error": str(e)
                })

    async def send_data(self, hex_data: str):
        """发送数据到BLE设备"""
        try:
            if not self.client or not self.client.is_connected:
                self.send_message({
                    "type": "send_result",
                    "success": False,
                    "error": "设备未连接"
                })
                return False

            # 将十六进制字符串转换为字节
            data = bytes.fromhex(hex_data.replace(" ", ""))

            # 记录发送的数据
            self.log(f"发送数据: {' '.join(f'{b:02X}' for b in data)}", "TX")

            # 写入数据 (Write Without Response)
            await self.client.write_gatt_char(
                self.RX_CHAR_UUID,
                data,
                response=False  # Write without response
            )

            self.send_message({
                "type": "send_result",
                "success": True,
                "bytes_sent": len(data)
            })

            return True

        except Exception as e:
            self.log(f"发送数据失败: {str(e)}", "ERROR")
            self.send_message({
                "type": "send_result",
                "success": False,
                "error": str(e)
            })
            return False

    def notification_handler(self, sender, data: bytearray):
        """处理接收到的通知数据"""
        # [FIX] 如果正在关闭，不处理通知
        if self._shutting_down or self._pipe_closed:
            return

        try:
            # 将数据转换为十六进制字符串
            hex_data = ' '.join(f'{b:02X}' for b in data)

            self.log(f"收到数据: {hex_data}", "RX")

            self.send_message({
                "type": "notification",
                "data": hex_data,
                "bytes": list(data),
                "length": len(data)
            })

        except Exception as e:
            if not self._pipe_closed:
                self.log(f"处理通知失败: {str(e)}", "ERROR")

    async def get_device_info(self):
        """获取设备信息"""
        try:
            if not self.client or not self.client.is_connected:
                self.send_message({
                    "type": "device_info",
                    "connected": False
                })
                return

            # 获取所有服务
            services = self.client.services

            services_info = []
            for service in services:
                chars_info = []
                for char in service.characteristics:
                    chars_info.append({
                        "uuid": str(char.uuid),
                        "properties": char.properties
                    })

                services_info.append({
                    "uuid": str(service.uuid),
                    "characteristics": chars_info
                })

            self.send_message({
                "type": "device_info",
                "connected": True,
                "address": self.device_address,
                "name": self.device_name,
                "services": services_info
            })

        except Exception as e:
            self.log(f"获取设备信息失败: {str(e)}", "ERROR")

    async def process_command(self, command: dict):
        """处理来自Java端的命令"""
        try:
            action = command.get("action")

            if action == "scan":
                timeout = command.get("timeout", 10.0)
                await self.scan_devices(timeout)

            elif action == "connect":
                address = command.get("address")
                await self.connect(address)

            elif action == "disconnect":
                await self.disconnect()

            elif action == "send":
                hex_data = command.get("data")
                if hex_data:
                    await self.send_data(hex_data)
                else:
                    self.send_message({
                        "type": "send_result",
                        "success": False,
                        "error": "No data provided"
                    })

            elif action == "info":
                await self.get_device_info()

            elif action == "exit":
                # [FIX] 标记正在关闭
                self._shutting_down = True
                await self.disconnect()
                return False  # 退出主循环

            else:
                self.log(f"未知命令: {action}", "WARNING")

        except Exception as e:
            if not self._pipe_closed:
                self.log(f"处理命令失败: {str(e)}", "ERROR")
                traceback.print_exc(file=sys.stderr)

        return True  # 继续主循环

    async def run(self):
        """主循环 - 接收并处理Java端的命令"""
        self.log("BLE服务已启动", "INFO")
        self.send_message({
            "type": "ready",
            "version": "1.0.0"
        })

        # 创建队列用于线程安全的命令传递
        command_queue = asyncio.Queue()

        # 启动标准输入读取任务
        async def stdin_reader():
            loop = asyncio.get_event_loop()
            while not self._shutting_down:
                try:
                    # 在执行器中运行阻塞的stdin读取
                    line = await loop.run_in_executor(None, sys.stdin.readline)

                    if not line:  # EOF - Java端已关闭
                        # [FIX] 标记管道已关闭，避免后续写入
                        self._pipe_closed = True
                        self._shutting_down = True
                        await command_queue.put({"action": "exit"})
                        break

                    line = line.strip()
                    if line:
                        try:
                            command = json.loads(line)
                            await command_queue.put(command)
                        except json.JSONDecodeError as e:
                            if not self._pipe_closed:
                                self.log(f"JSON解析错误: {e}", "ERROR")

                except Exception as e:
                    if not self._shutting_down and not self._pipe_closed:
                        self.log(f"读取输入失败: {e}", "ERROR")
                    break

        # 启动stdin读取任务
        stdin_task = asyncio.create_task(stdin_reader())

        # 主命令处理循环
        try:
            while not self._shutting_down:
                try:
                    # 等待命令（超时检查连接状态）
                    command = await asyncio.wait_for(
                        command_queue.get(),
                        timeout=30.0
                    )

                    # 处理命令
                    if not await self.process_command(command):
                        break

                except asyncio.TimeoutError:
                    # 定期检查连接状态
                    if self.client and self.is_connected and not self._pipe_closed:
                        if not self.client.is_connected:
                            self.is_connected = False
                            self.log("设备连接已断开", "WARNING")
                            self.send_message({
                                "type": "connection_lost"
                            })

                except Exception as e:
                    if not self._pipe_closed:
                        self.log(f"命令处理错误: {e}", "ERROR")

        finally:
            stdin_task.cancel()
            try:
                await stdin_task
            except asyncio.CancelledError:
                pass

            # [FIX] 静默断开连接
            self._shutting_down = True
            await self.disconnect()

            if not self._pipe_closed:
                self.log("BLE服务已停止", "INFO")


def main():
    """主入口"""
    # 设置事件循环策略（Windows需要）
    if sys.platform == 'win32':
        asyncio.set_event_loop_policy(asyncio.WindowsProactorEventLoopPolicy())

    # 运行服务
    service = BLEService()

    try:
        asyncio.run(service.run())
    except KeyboardInterrupt:
        # [FIX] 键盘中断时也检查管道状态
        if not service._pipe_closed:
            try:
                print(json.dumps({
                    "type": "log",
                    "level": "INFO",
                    "message": "BLE服务被用户中断"
                }), flush=True)
            except:
                pass
    except (OSError, IOError, BrokenPipeError):
        # [FIX] 管道错误静默退出，不弹窗
        pass
    except Exception as e:
        # [FIX] 其他异常写入stderr而不是stdout
        try:
            sys.stderr.write(f"BLE服务异常: {str(e)}\n")
            traceback.print_exc(file=sys.stderr)
        except:
            pass


if __name__ == "__main__":
    main()