from typing import Dict, Optional, Tuple, List
import asyncio
import sortedcontainers
import re
import secrets
import collections
import logging

from gear import Database
from hailtop import aiotools
from hailtop.utils import time_msecs, secret_alnum_string, periodically_call

from ...instance_config import QuantifiedResource
from ...batch_configuration import WORKER_MAX_IDLE_TIME_MSECS
from ..instance import Instance
from ..location import CloudLocationMonitor
from ..resource_manager import (CloudResourceManager, VMStateCreating, VMStateRunning,
                                VMStateTerminated, VMDoesNotExist)


log = logging.getLogger('inst_coll_manager')


class InstanceCollectionManager:
    def __init__(self,
                 db: Database,  # BORROWED
                 machine_name_prefix: str,
                 location_monitor: CloudLocationMonitor,
                 ):
        self.db: Database = db
        self.machine_name_prefix = machine_name_prefix
        self.location_monitor = location_monitor

        self.inst_coll_regex = re.compile(f'{self.machine_name_prefix}(?P<inst_coll>.*)-.*')
        self.name_inst_coll: Dict[str, InstanceCollection] = {}

    def register_instance_collection(self, inst_coll: 'InstanceCollection'):
        assert inst_coll.name not in self.name_inst_coll
        self.name_inst_coll[inst_coll.name] = inst_coll

    def choose_location(self,
                        cores: int,
                        local_ssd_data_disk: bool,
                        data_disk_size_gb: int
                        ) -> str:
        if self.global_live_total_cores_mcpu // 1000 < 1_000:
            return self.location_monitor.default_location()
        return self.location_monitor.choose_location(
            cores, local_ssd_data_disk, data_disk_size_gb)

    @property
    def pools(self) -> Dict[str, 'InstanceCollection']:
        return {k: v for k, v in self.name_inst_coll.items() if v.is_pool}

    @property
    def name_instance(self):
        result = {}
        for inst_coll in self.name_inst_coll.values():
            result.update(inst_coll.name_instance)
        return result

    @property
    def global_live_total_cores_mcpu(self):
        return sum([inst_coll.live_total_cores_mcpu for inst_coll in self.name_inst_coll.values()])

    @property
    def global_live_free_cores_mcpu(self):
        return sum([inst_coll.live_free_cores_mcpu for inst_coll in self.name_inst_coll.values()])

    @property
    def global_n_instances_by_state(self):
        counters = [collections.Counter(inst_coll.n_instances_by_state) for inst_coll in self.name_inst_coll.values()]
        result = collections.Counter({})
        for counter in counters:
            result += counter
        return result

    def get_inst_coll(self, inst_coll_name):
        return self.name_inst_coll.get(inst_coll_name)

    def get_instance(self, inst_name):
        inst_coll_name = None

        match = re.search(self.inst_coll_regex, inst_name)
        if match:
            inst_coll_name = match.groupdict()['inst_coll']
        elif inst_name.startswith(self.machine_name_prefix):
            inst_coll_name = 'standard'

        inst_coll = self.name_inst_coll.get(inst_coll_name)
        if inst_coll:
            return inst_coll.name_instance.get(inst_name)
        return None


class InstanceCollection:
    def __init__(self,
                 db: Database,  # BORROWED
                 inst_coll_manager: InstanceCollectionManager,
                 resource_manager: CloudResourceManager,
                 cloud: str,
                 name: str,
                 machine_name_prefix: str,
                 is_pool: bool,
                 max_instances: int,
                 max_live_instances: int,
                 task_manager: aiotools.BackgroundTaskManager  # BORROWED
                 ):
        self.db = db
        self.inst_coll_manager = inst_coll_manager
        self.resource_manager = resource_manager
        self.cloud = cloud
        self.name = name
        self.machine_name_prefix = f'{machine_name_prefix}{self.name}-'
        self.is_pool = is_pool
        self.max_instances = max_instances
        self.max_live_instances = max_live_instances

        self.name_instance: Dict[str, Instance] = {}
        self.live_free_cores_mcpu_by_location: Dict[str, int] = collections.defaultdict(int)

        self.instances_by_last_updated = sortedcontainers.SortedSet(key=lambda instance: instance.last_updated)

        self.n_instances_by_state = {'pending': 0, 'active': 0, 'inactive': 0, 'deleted': 0}

        # pending and active
        self.live_free_cores_mcpu = 0
        self.live_total_cores_mcpu = 0

        task_manager.ensure_future(self.monitor_instances_loop())
        self.inst_coll_manager.register_instance_collection(self)

    @property
    def n_instances(self) -> int:
        return len(self.name_instance)

    def choose_location(self,
                        cores: int,
                        local_ssd_data_disk: bool,
                        data_disk_size_gb: int
                        ) -> str:
        return self.inst_coll_manager.choose_location(cores,
                                                      local_ssd_data_disk,
                                                      data_disk_size_gb)

    def generate_machine_name(self) -> str:
        while True:
            # 36 ** 5 = ~60M
            suffix = secret_alnum_string(5, case='lower')
            machine_name = f'{self.machine_name_prefix}{suffix}'
            if machine_name not in self.name_instance:
                break
        return machine_name

    def adjust_for_remove_instance(self, instance: Instance):
        assert instance in self.instances_by_last_updated

        self.instances_by_last_updated.remove(instance)

        self.n_instances_by_state[instance.state] -= 1

        if instance.state in ('pending', 'active'):
            self.live_free_cores_mcpu -= max(0, instance.free_cores_mcpu)
            self.live_total_cores_mcpu -= instance.cores_mcpu
            self.live_free_cores_mcpu_by_location[instance.location] -= max(0, instance.free_cores_mcpu)

    async def remove_instance(self,
                              instance: Instance,
                              reason: str,
                              timestamp: Optional[int] = None):
        await instance.deactivate(reason, timestamp)

        await self.db.just_execute('UPDATE instances SET removed = 1 WHERE name = %s;', (instance.name,))

        self.adjust_for_remove_instance(instance)
        del self.name_instance[instance.name]

    def adjust_for_add_instance(self, instance: Instance):
        assert instance not in self.instances_by_last_updated

        self.n_instances_by_state[instance.state] += 1

        self.instances_by_last_updated.add(instance)
        if instance.state in ('pending', 'active'):
            self.live_free_cores_mcpu += max(0, instance.free_cores_mcpu)
            self.live_total_cores_mcpu += instance.cores_mcpu
            self.live_free_cores_mcpu_by_location[instance.location] += max(0, instance.free_cores_mcpu)

    def add_instance(self, instance: Instance):
        assert instance.name not in self.name_instance

        self.name_instance[instance.name] = instance
        self.adjust_for_add_instance(instance)

    async def _create_instance(self,
                               app,
                               cores: int,
                               machine_type: str,
                               job_private: bool,
                               location: Optional[str],
                               preemptible: bool,
                               max_idle_time_msecs: Optional[int],
                               local_ssd_data_disk,
                               data_disk_size_gb,
                               boot_disk_size_gb,
                               ) -> Tuple[Instance, List[QuantifiedResource]]:
        if location is None:
            location = self.choose_location(cores,
                                            local_ssd_data_disk,
                                            data_disk_size_gb)

        if max_idle_time_msecs is None:
            max_idle_time_msecs = WORKER_MAX_IDLE_TIME_MSECS

        machine_name = self.generate_machine_name()
        activation_token = secrets.token_urlsafe(32)
        instance_config = self.resource_manager.instance_config(
            machine_type=machine_type,
            preemptible=preemptible,
            local_ssd_data_disk=local_ssd_data_disk,
            data_disk_size_gb=data_disk_size_gb,
            boot_disk_size_gb=boot_disk_size_gb,
            job_private=job_private,
            location=location,
        )
        instance = await Instance.create(
            app=app,
            inst_coll=self,
            name=machine_name,
            activation_token=activation_token,
            cores=cores,
            location=location,
            machine_type=machine_type,
            preemptible=True,
            instance_config=instance_config
        )
        self.add_instance(instance)
        total_resources_on_instance = await self.resource_manager.create_vm(
            file_store=app['file_store'],
            machine_name=machine_name,
            activation_token=activation_token,
            max_idle_time_msecs=max_idle_time_msecs,
            local_ssd_data_disk=local_ssd_data_disk,
            data_disk_size_gb=data_disk_size_gb,
            boot_disk_size_gb=boot_disk_size_gb,
            preemptible=preemptible,
            job_private=job_private,
            location=location,
            machine_type=machine_type,
            instance_config=instance_config,
        )

        return (instance, total_resources_on_instance)

    async def call_delete_instance(self,
                                   instance: Instance,
                                   reason: str,
                                   timestamp: Optional[int] = None,
                                   force: bool = False):
        if instance.state == 'deleted' and not force:
            return
        if instance.state not in ('inactive', 'deleted'):
            await instance.deactivate(reason, timestamp)

        try:
            await self.resource_manager.delete_vm(instance)
        except VMDoesNotExist:
            log.info(f'{instance} delete already done')
            await self.remove_instance(instance, reason, timestamp)

    async def check_on_instance(self, instance: Instance):
        active_and_healthy = await instance.check_is_active_and_healthy()

        if (instance.state == 'active'
                and instance.failed_request_count > 5
                and time_msecs() - instance.last_updated > 5 * 60 * 1000):
            log.exception(f'deleting {instance} with {instance.failed_request_count} failed request counts after more than 5 minutes')
            await self.call_delete_instance(instance, 'not_responding')
            return

        if active_and_healthy:
            return

        try:
            vm_state = await self.resource_manager.get_vm_state(instance)
        except VMDoesNotExist:
            await self.remove_instance(instance, 'does_not_exist')
            return

        log.info(f'{instance} vm_state {vm_state}')

        if (instance.state == 'pending'
                and isinstance(vm_state, (VMStateCreating, VMStateRunning))
                and vm_state.time_since_last_state_change() > 5 * 60 * 1000):
            log.exception(f'{instance} (state: {str(vm_state)}) has made no progress in last 5m, deleting')
            await self.call_delete_instance(instance, 'activation_timeout')
        elif isinstance(vm_state, VMStateTerminated):
            log.info(f'{instance} live but stopping or terminated, deactivating')
            await instance.deactivate('terminated')
        elif instance.state == 'inactive':
            log.info(f'{instance} (vm_state: {vm_state}) is inactive, deleting')
            await self.call_delete_instance(instance, 'inactive')

        await instance.update_timestamp()

    async def monitor_instances(self):
        if self.instances_by_last_updated:
            # [:50] are the fifty smallest (oldest)
            instances = self.instances_by_last_updated[:50]

            async def check(instance):
                since_last_updated = time_msecs() - instance.last_updated
                if since_last_updated > 60 * 1000:
                    log.info(f'checking on {instance}, last updated {since_last_updated / 1000}s ago')
                    await self.check_on_instance(instance)

            await asyncio.gather(*[check(instance) for instance in instances])

    async def monitor_instances_loop(self):
        await periodically_call(1, self.monitor_instances)
