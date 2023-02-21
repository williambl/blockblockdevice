# Example Python plugin.
#
# This example can be freely used for any purpose.
import math

# Run it from the build directory like this:
#
#   ./nbdkit -f -v python ./plugins/python/examples/ramdisk.py test1=foo
#
# Or run it after installing nbdkit like this:
#
#   nbdkit -f -v python ./plugins/python/examples/ramdisk.py test1=foo
#
# The -f -v arguments are optional.  They cause the server to stay in
# the foreground and print debugging, which is useful when testing.
#
# You can connect to the server using guestfish or qemu, eg:
#
#   guestfish --format=raw -a nbd://localhost
#   ><fs> run
#   ><fs> part-disk /dev/sda mbr
#   ><fs> mkfs ext2 /dev/sda1
#   ><fs> list-filesystems
#   ><fs> mount /dev/sda1 /
#   ><fs> [etc]

import nbdkit
import errno
import requests
import base64

# There are several variants of the API.  nbdkit will call this
# function first to determine which one you want to use.  This is the
# latest version at the time this example was written.
API_VERSION = 2

disk_length = 65536
bytes_per_chunk = 3064


# This just prints the extra command line parameters, but real plugins
# should parse them and reject any unknown parameters.
def config(key, value):
    nbdkit.debug("ignored parameter %s=%s" % (key, value))


def open(readonly):
    nbdkit.debug("open: readonly=%d" % readonly)

    # You can return any non-NULL Python object from open, and the
    # same object will be passed as the first arg to the other
    # callbacks [in the client connected phase].
    return 1


def get_size(h):
    global disk_length
    return disk_length


def pread(h, buf, offset, flags):
    global disk_length
    data = get_relevant_data(len(buf), offset)
    buf[:len(data)] = data


def pwrite(h, buf, offset, flags):
    global disk
    write_data(len(buf), offset, buf)


def zero(h, count, offset, flags):
    global disk
    write_data(count, offset, bytearray(count))


def get_relevant_data(length, offset):
    global disk_length, bytes_per_chunk
    data = bytes()
    while length > 0:
        chunk_offset = math.floor(offset/bytes_per_chunk)
        within_chunk_offset = offset % bytes_per_chunk
        length_in_chunk = min(length, bytes_per_chunk - within_chunk_offset)
        print(f"http://localhost:8394/read_chunk?x={chunk_offset}&z=0&length={length_in_chunk}&offset={within_chunk_offset}")
        data += base64.b64decode(requests.get(f"http://localhost:8394/read_chunk?x={chunk_offset}&z=0&length={length_in_chunk}&offset={within_chunk_offset}").text)
        length -= length_in_chunk
        offset += length_in_chunk
    return data


def write_data(length, offset, data):
    global disk_length, bytes_per_chunk
    while length > 0:
        chunk_offset = math.floor(offset/bytes_per_chunk)
        within_chunk_offset = offset % bytes_per_chunk
        length_in_chunk = min(length, bytes_per_chunk - within_chunk_offset)
        print(f"http://localhost:8394/write_chunk?x={chunk_offset}&z=0&offset={within_chunk_offset}")
        requests.put(f"http://localhost:8394/write_chunk?x={chunk_offset}&z=0&offset={within_chunk_offset}", data=base64.b64encode(data[len(data)-length:len(data)-length+length_in_chunk]))
        length -= length_in_chunk
        offset += length_in_chunk
    return data

